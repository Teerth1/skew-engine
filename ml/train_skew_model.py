"""
train_skew_model.py
===================

End-to-end ML training pipeline for the Skew Engine.

Consumes the timestamped Schwab option-chain snapshots archived by
`SchwabDataGrabberService` (data/schwab_snapshots/*.json), engineers a
quantitative feature matrix (spot dynamics, skew shape, GEX structure,
IV rank, liquidity, second-order Greeks), labels each observation with a
triple-barrier outcome (+40% TP before -20% SL within 10 trading days on
an ATM option), and trains an XGBoost classifier with walk-forward
TimeSeriesSplit cross-validation.

Outputs
-------
  ml/artifacts/skew_ml_model.json      trained XGBoost booster
  ml/artifacts/feature_columns.json    ordered feature list (used by the
                                       FastAPI inference server)
  ml/artifacts/training_report.txt     CV metrics + feature importances

Usage
-----
  python ml/train_skew_model.py [--data-dir data/schwab_snapshots]
                                [--symbol SPX] [--horizon-days 10]
                                [--tp 0.40] [--sl -0.20]
"""

from __future__ import annotations

import argparse
import json
import logging
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import norm
from sklearn.metrics import classification_report, roc_auc_score
from sklearn.model_selection import TimeSeriesSplit
from xgboost import XGBClassifier

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("train_skew_model")

RISK_FREE_RATE = 0.05
TRADING_DAYS_PER_YEAR = 252

# ---------------------------------------------------------------------------
# 1. DATA LOADER — parse SchwabDataGrabberService archive files
# ---------------------------------------------------------------------------


@dataclass
class ChainSnapshot:
    """One archived option-chain observation, flattened for feature work."""

    captured_at: pd.Timestamp
    symbol: str
    spot: float
    # Per-contract table: strike, dte, type, iv, delta, gamma, theta, vega,
    # bid, ask, open_interest, volume
    contracts: pd.DataFrame = field(repr=False)


def _iter_contracts(exp_date_map: dict, option_type: str):
    """Yield contract dicts from Schwab's {expiry: {strike: [contract]}} maps."""
    if not isinstance(exp_date_map, dict):
        return
    for _expiry, strikes in exp_date_map.items():
        if not isinstance(strikes, dict):
            continue
        for strike_str, contract_list in strikes.items():
            if not contract_list:
                continue
            c = contract_list[0]
            iv = float(c.get("volatility", 0.0) or 0.0)
            # Schwab reports IV in percent (e.g. 18.5); normalize to decimal.
            if iv > 3.0:
                iv /= 100.0
            yield {
                "type": option_type,
                "strike": float(strike_str),
                "dte": int(c.get("daysToExpiration", 0) or 0),
                "iv": iv,
                "delta": float(c.get("delta", 0.0) or 0.0),
                "gamma": float(c.get("gamma", 0.0) or 0.0),
                "theta": float(c.get("theta", 0.0) or 0.0),
                "vega": float(c.get("vega", 0.0) or 0.0),
                "bid": float(c.get("bid", 0.0) or 0.0),
                "ask": float(c.get("ask", 0.0) or 0.0),
                "open_interest": float(c.get("openInterest", 0.0) or 0.0),
                "volume": float(c.get("totalVolume", 0.0) or 0.0),
            }


def load_snapshots(data_dir: Path, symbol: str) -> list[ChainSnapshot]:
    """Load every archived JSON snapshot for `symbol`, sorted by capture time."""
    files = sorted(data_dir.glob(f"{symbol}_chain_*.json"))
    if not files:
        # Fall back to every file, filtering on the embedded symbol field.
        files = sorted(data_dir.glob("*.json"))

    snapshots: list[ChainSnapshot] = []
    for path in files:
        try:
            with open(path, encoding="utf-8") as fh:
                wrapper = json.load(fh)
        except (json.JSONDecodeError, OSError) as exc:
            log.warning("Skipping unreadable snapshot %s: %s", path.name, exc)
            continue

        if wrapper.get("symbol", "").upper() != symbol.upper():
            continue

        chain = wrapper.get("data") or {}
        spot = float(chain.get("underlyingPrice", 0.0) or 0.0)
        if spot <= 0.0:
            continue

        rows = list(_iter_contracts(chain.get("callExpDateMap"), "call"))
        rows += list(_iter_contracts(chain.get("putExpDateMap"), "put"))
        if not rows:
            continue

        snapshots.append(
            ChainSnapshot(
                captured_at=pd.Timestamp(wrapper["capturedAt"]),
                symbol=wrapper["symbol"],
                spot=spot,
                contracts=pd.DataFrame(rows),
            )
        )

    snapshots.sort(key=lambda s: s.captured_at)
    log.info("Loaded %d snapshots for %s from %s", len(snapshots), symbol, data_dir)
    return snapshots


# ---------------------------------------------------------------------------
# 2. PER-SNAPSHOT CROSS-SECTIONAL FEATURES
# ---------------------------------------------------------------------------


def _target_expiry_slice(df: pd.DataFrame, min_dte: int = 14, max_dte: int = 60) -> pd.DataFrame:
    """Pick the single expiry closest to 30 DTE within [min_dte, max_dte]."""
    eligible = df[(df["dte"] >= min_dte) & (df["dte"] <= max_dte)]
    if eligible.empty:
        eligible = df[df["dte"] > 0]
    if eligible.empty:
        return df
    best_dte = eligible.loc[(eligible["dte"] - 30).abs().idxmin(), "dte"]
    return df[df["dte"] == best_dte]


def _iv_at_moneyness(chain: pd.DataFrame, spot: float, moneyness: float) -> float:
    """Linearly interpolate IV at strike = moneyness * spot.

    Uses puts below spot and calls above (the liquid OTM side), mirroring how
    a skew curve is quoted.
    """
    target = moneyness * spot
    side = chain[chain["type"] == ("put" if moneyness <= 1.0 else "call")]
    side = side[side["iv"] > 0].sort_values("strike")
    if len(side) < 2:
        side = chain[chain["iv"] > 0].sort_values("strike")
    if len(side) < 2:
        return np.nan
    return float(np.interp(target, side["strike"].values, side["iv"].values))


def _gex_profile(chain: pd.DataFrame, spot: float) -> dict:
    """Dealer gamma-exposure ladder (mirrors GexService.java formula)."""
    df = chain.copy()
    sign = np.where(df["type"] == "call", 1.0, -1.0)
    df["gex"] = sign * df["gamma"] * df["open_interest"] * 100.0 * spot * spot * 0.01
    ladder = df.groupby("strike")["gex"].sum().sort_index()
    if ladder.empty:
        return {"call_wall": np.nan, "put_wall": np.nan, "zero_flip": np.nan, "net_gex": np.nan}

    call_wall = float(ladder.idxmax())
    put_wall = float(ladder.idxmin())

    zero_flip = np.nan
    below = ladder[ladder.index <= spot].sort_index(ascending=False)
    negatives = below[below < 0]
    if not negatives.empty:
        zero_flip = float(negatives.index[0])

    return {
        "call_wall": call_wall,
        "put_wall": put_wall,
        "zero_flip": zero_flip,
        "net_gex": float(ladder.sum()),
    }


def _second_order_greeks(spot: float, strike: float, dte: int, iv: float) -> dict:
    """Black-Scholes Vanna, Charm (call), and Vomma for the ATM contract."""
    t = max(dte, 1) / 365.0
    if iv <= 0 or spot <= 0 or strike <= 0:
        return {"vanna": np.nan, "charm": np.nan, "vomma": np.nan}
    sqrt_t = math.sqrt(t)
    d1 = (math.log(spot / strike) + (RISK_FREE_RATE + 0.5 * iv * iv) * t) / (iv * sqrt_t)
    d2 = d1 - iv * sqrt_t
    pdf_d1 = norm.pdf(d1)
    vanna = -pdf_d1 * d2 / iv                      # dDelta/dVol (per 100% vol)
    charm = -pdf_d1 * (2 * RISK_FREE_RATE * t - d2 * iv * sqrt_t) / (2 * t * iv * sqrt_t)
    vomma = spot * pdf_d1 * sqrt_t * d1 * d2 / iv  # dVega/dVol
    return {"vanna": vanna, "charm": charm, "vomma": vomma}


def snapshot_features(snap: ChainSnapshot) -> dict:
    """Compute the full cross-sectional feature row for one snapshot."""
    chain = _target_expiry_slice(snap.contracts)
    spot = snap.spot

    iv_090 = _iv_at_moneyness(chain, spot, 0.90)
    iv_095 = _iv_at_moneyness(chain, spot, 0.95)
    iv_100 = _iv_at_moneyness(chain, spot, 1.00)
    iv_105 = _iv_at_moneyness(chain, spot, 1.05)
    iv_110 = _iv_at_moneyness(chain, spot, 1.10)

    gex = _gex_profile(snap.contracts, spot)

    # ATM contract (nearest strike, per side) for liquidity + Greeks
    atm = chain.iloc[(chain["strike"] - spot).abs().argsort()[:4]]
    atm_liquid = atm[(atm["bid"] > 0) & (atm["ask"] > 0)]
    mid = (atm_liquid["bid"] + atm_liquid["ask"]) / 2.0
    spread_pct = float(((atm_liquid["ask"] - atm_liquid["bid"]) / mid.replace(0, np.nan)).mean())
    vol_oi = float((atm["volume"] / atm["open_interest"].replace(0, np.nan)).mean())

    atm_dte = int(chain["dte"].iloc[0]) if not chain.empty else 30
    atm_iv = iv_100 if not np.isnan(iv_100) else float(chain["iv"].median())
    greeks2 = _second_order_greeks(spot, spot, atm_dte, atm_iv)

    def pct_dist(level: float) -> float:
        return (level - spot) / spot if level and not np.isnan(level) else np.nan

    return {
        "captured_at": snap.captured_at,
        "spot": spot,
        "atm_iv": atm_iv,
        "atm_dte": atm_dte,
        # Skew shape
        "skew_95_105": iv_095 - iv_105,
        "skew_curvature": iv_090 - 2.0 * iv_100 + iv_110,
        "put_call_atm_skew": _atm_put_call_skew(chain, spot),
        # GEX structure
        "gex_net": gex["net_gex"],
        "dist_call_wall": pct_dist(gex["call_wall"]),
        "dist_put_wall": pct_dist(gex["put_wall"]),
        "dist_zero_flip": pct_dist(gex["zero_flip"]),
        # Liquidity / flow
        "bid_ask_spread_pct": spread_pct,
        "volume_oi_ratio": vol_oi,
        # Second-order Greeks (dealer-flow precursors)
        "vanna": greeks2["vanna"],
        "charm": greeks2["charm"],
        "vomma": greeks2["vomma"],
    }


def _atm_put_call_skew(chain: pd.DataFrame, spot: float) -> float:
    """IV(ATM put) - IV(ATM call): the raw signal the Java engine trades on."""
    out = {}
    for side in ("put", "call"):
        s = chain[(chain["type"] == side) & (chain["iv"] > 0)]
        if s.empty:
            return np.nan
        out[side] = float(s.iloc[(s["strike"] - spot).abs().argsort()[:1]]["iv"].iloc[0])
    return out["put"] - out["call"]


# ---------------------------------------------------------------------------
# 3. TIME-SERIES FEATURES (velocity, momentum, IV rank)
# ---------------------------------------------------------------------------


def add_time_series_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.sort_values("captured_at").reset_index(drop=True)

    for n in (1, 3, 5, 10):
        df[f"spot_ret_{n}"] = df["spot"].pct_change(n)
        df[f"skew_mom_{n}"] = df["put_call_atm_skew"].diff(n)
    # Acceleration = change of 1-period velocity
    for n in (3, 5, 10):
        df[f"spot_accel_{n}"] = df["spot_ret_1"].diff(n)

    # IV Rank & Percentile over a rolling window (~2 weeks of 5-min snapshots)
    window = min(max(len(df) // 4, 30), 780)
    roll = df["atm_iv"].rolling(window, min_periods=10)
    rng = (roll.max() - roll.min()).replace(0, np.nan)
    df["iv_rank"] = (df["atm_iv"] - roll.min()) / rng
    df["iv_percentile"] = df["atm_iv"].rolling(window, min_periods=10).apply(
        lambda x: (x < x[-1]).mean(), raw=True
    )
    return df


# ---------------------------------------------------------------------------
# 4. TRIPLE-BARRIER TARGET LABELING (no lookahead into features)
# ---------------------------------------------------------------------------


def _bs_price(spot: float, strike: float, t: float, iv: float, kind: str) -> float:
    if t <= 0 or iv <= 0:
        return max(spot - strike, 0.0) if kind == "call" else max(strike - spot, 0.0)
    sqrt_t = math.sqrt(t)
    d1 = (math.log(spot / strike) + (RISK_FREE_RATE + 0.5 * iv * iv) * t) / (iv * sqrt_t)
    d2 = d1 - iv * sqrt_t
    disc = math.exp(-RISK_FREE_RATE * t)
    if kind == "call":
        return spot * norm.cdf(d1) - strike * disc * norm.cdf(d2)
    return strike * disc * norm.cdf(-d2) - spot * norm.cdf(-d1)


def label_triple_barrier(
    df: pd.DataFrame,
    option_type: str,
    horizon_days: int = 10,
    take_profit: float = 0.40,
    stop_loss: float = -0.20,
) -> pd.Series:
    """Y=1 if an ATM option bought at row i hits +TP before -SL within horizon.

    The forward option price path is marked with Black-Scholes using the
    *future* snapshots' spot and ATM IV while decaying time-to-expiry — this
    captures both delta P&L and vol-crush/theta, which raw spot moves miss.
    """
    n = len(df)
    labels = np.zeros(n, dtype=int)
    ts = df["captured_at"].values
    horizon = pd.Timedelta(days=horizon_days * 7 / 5)  # trading→calendar days

    spots = df["spot"].values
    ivs = df["atm_iv"].values
    dtes = df["atm_dte"].values

    for i in range(n):
        strike = spots[i]
        t0 = dtes[i] / 365.0
        entry = _bs_price(spots[i], strike, t0, ivs[i], option_type)
        if entry <= 0:
            continue
        deadline = ts[i] + horizon
        j = i + 1
        while j < n and ts[j] <= deadline:
            elapsed = (ts[j] - ts[i]) / np.timedelta64(1, "D") / 365.0
            price = _bs_price(spots[j], strike, max(t0 - elapsed, 1e-6), ivs[j], option_type)
            ret = price / entry - 1.0
            if ret <= stop_loss:
                break  # stopped out first → label stays 0
            if ret >= take_profit:
                labels[i] = 1
                break
            j += 1
    return pd.Series(labels, index=df.index)


# ---------------------------------------------------------------------------
# 5. TRAINING & EVALUATION
# ---------------------------------------------------------------------------

FEATURE_COLUMNS = [
    "atm_iv", "skew_95_105", "skew_curvature", "put_call_atm_skew",
    "gex_net", "dist_call_wall", "dist_put_wall", "dist_zero_flip",
    "bid_ask_spread_pct", "volume_oi_ratio",
    "vanna", "charm", "vomma",
    "spot_ret_1", "spot_ret_3", "spot_ret_5", "spot_ret_10",
    "spot_accel_3", "spot_accel_5", "spot_accel_10",
    "skew_mom_1", "skew_mom_3", "skew_mom_5", "skew_mom_10",
    "iv_rank", "iv_percentile",
    "option_type_flag",  # +1 = call, -1 = put
]


def build_dataset(df: pd.DataFrame, horizon_days: int, tp: float, sl: float) -> pd.DataFrame:
    """Two rows per snapshot — one long-call and one long-put candidate —
    each labeled with its own triple-barrier outcome."""
    frames = []
    for kind, flag in (("call", 1.0), ("put", -1.0)):
        part = df.copy()
        part["option_type_flag"] = flag
        part["y"] = label_triple_barrier(df, kind, horizon_days, tp, sl)
        frames.append(part)
    out = pd.concat(frames, ignore_index=True).sort_values("captured_at", kind="stable")
    # Drop the tail whose label window extends past the data (right-censored)
    cutoff = df["captured_at"].max() - pd.Timedelta(days=horizon_days * 7 / 5)
    return out[out["captured_at"] <= cutoff].reset_index(drop=True)


def train(df: pd.DataFrame, artifacts_dir: Path) -> None:
    X = df[FEATURE_COLUMNS]
    y = df["y"]
    log.info("Dataset: %d rows, positive rate %.1f%%", len(df), 100 * y.mean())

    pos = max(int(y.sum()), 1)
    model_params = dict(
        n_estimators=400,
        max_depth=4,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        min_child_weight=5,
        reg_lambda=2.0,
        scale_pos_weight=(len(y) - pos) / pos,
        eval_metric="auc",
        tree_method="hist",
        random_state=42,
    )

    # Walk-forward CV — folds always train on the past, test on the future.
    tscv = TimeSeriesSplit(n_splits=5)
    report_lines, aucs = [], []
    for fold, (tr_idx, te_idx) in enumerate(tscv.split(X), start=1):
        model = XGBClassifier(**model_params)
        model.fit(X.iloc[tr_idx], y.iloc[tr_idx], verbose=False)
        proba = model.predict_proba(X.iloc[te_idx])[:, 1]
        y_te = y.iloc[te_idx]
        if y_te.nunique() < 2:
            log.warning("Fold %d has a single class in test — skipping AUC", fold)
            continue
        auc = roc_auc_score(y_te, proba)
        aucs.append(auc)
        report_lines.append(f"--- Fold {fold} | ROC-AUC {auc:.4f} ---")
        report_lines.append(classification_report(y_te, (proba >= 0.5).astype(int), zero_division=0))
        log.info("Fold %d ROC-AUC: %.4f", fold, auc)

    mean_auc = float(np.mean(aucs)) if aucs else float("nan")
    log.info("Mean walk-forward ROC-AUC: %.4f", mean_auc)

    # Fit the final model on all data for deployment
    final_model = XGBClassifier(**model_params)
    final_model.fit(X, y, verbose=False)

    importances = pd.Series(final_model.feature_importances_, index=FEATURE_COLUMNS)
    top15 = importances.sort_values(ascending=False).head(15)
    log.info("Top 15 feature importances:\n%s", top15.to_string())

    artifacts_dir.mkdir(parents=True, exist_ok=True)
    final_model.save_model(artifacts_dir / "skew_ml_model.json")
    (artifacts_dir / "feature_columns.json").write_text(json.dumps(FEATURE_COLUMNS, indent=2))
    (artifacts_dir / "training_report.txt").write_text(
        "\n".join(report_lines)
        + f"\n\nMean walk-forward ROC-AUC: {mean_auc:.4f}\n\nTop 15 features:\n{top15.to_string()}\n"
    )
    log.info("Artifacts written to %s", artifacts_dir.resolve())


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", default="data/schwab_snapshots", type=Path)
    parser.add_argument("--symbol", default="SPX")
    parser.add_argument("--horizon-days", default=10, type=int)
    parser.add_argument("--tp", default=0.40, type=float)
    parser.add_argument("--sl", default=-0.20, type=float)
    parser.add_argument("--artifacts-dir", default=Path(__file__).parent / "artifacts", type=Path)
    args = parser.parse_args()

    snapshots = load_snapshots(args.data_dir, args.symbol)
    if len(snapshots) < 50:
        log.error(
            "Only %d snapshots found — need at least ~50 for a meaningful model. "
            "Let SchwabDataGrabberService run longer, then retrain.", len(snapshots)
        )
        return 1

    rows = [snapshot_features(s) for s in snapshots]
    df = add_time_series_features(pd.DataFrame(rows))
    dataset = build_dataset(df, args.horizon_days, args.tp, args.sl)
    if dataset.empty or dataset["y"].nunique() < 2:
        log.error("Labeled dataset is empty or single-class — collect more history.")
        return 1

    train(dataset, args.artifacts_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
