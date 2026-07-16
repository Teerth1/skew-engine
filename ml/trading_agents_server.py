"""
trading_agents_server.py
========================

External intelligence engine for the Skew Engine Spring Boot backend.

Serves POST /analyze (the endpoint `TradingAgentsClientService` calls) and
runs a 3-node LangGraph workflow:

    Quant Analyst  ─┐
                    ├─>  Risk & Portfolio Manager  ─>  final rating
    Macro Analyst  ─┘

The Quant node scores the setup with the trained XGBoost model
(ml/artifacts/skew_ml_model.json), Black-Scholes context, and GEX structure
enriched from the newest local Schwab snapshot. The Macro node reads the
enriched news headlines. The Risk Manager synthesizes both plus current
portfolio exposure into the structured decision Spring Boot expects:

    {"rating": "...", "confidence": 0.0-1.0, "rationale": "...", "riskNotes": "..."}

LLM usage is optional: if GEMINI_API_KEY / GOOGLE_API_KEY is set, each node
reasons with Gemini via LangGraph; otherwise deterministic quantitative
fallbacks keep the service fully functional (and unit-testable) offline.

Run:  uvicorn trading_agents_server:app --port 8000   (from the ml/ directory)
"""

from __future__ import annotations

import json
import logging
import math
import os
from pathlib import Path
from typing import Optional, TypedDict

import numpy as np
from fastapi import FastAPI
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("trading_agents_server")

ARTIFACTS_DIR = Path(__file__).parent / "artifacts"
SNAPSHOT_DIR = Path(__file__).parent.parent / "data" / "schwab_snapshots"
RISK_FREE_RATE = 0.05

# ---------------------------------------------------------------------------
# Request / response schemas (mirror TradingAgentsClientService payload)
# ---------------------------------------------------------------------------


class MarketState(BaseModel):
    ticker: str = "SPY"
    spotPrice: float = 0.0
    putIv: float = 0.0
    callIv: float = 0.0
    skew: float = 0.0
    # Optional extras — the Java client sends these inside skewSignal today,
    # but accept them here too so the schema is forward-compatible.
    spotReturn: float = 0.0
    skewChange: float = 0.0


class SkewSignal(BaseModel):
    signalType: str = "NONE"           # BEARISH_DIVERGENCE / BULLISH_DIVERGENCE
    optionType: str = "call"           # "put" or "call"
    spotReturn: float = 0.0
    skewChange: float = 0.0


class AnalyzeRequest(BaseModel):
    marketState: MarketState
    skewSignal: SkewSignal = Field(default_factory=SkewSignal)
    recentNews: list[str] = Field(default_factory=list)
    currentPositions: int = 0
    greeksProfile: Optional[dict] = None  # optional GEX/Greeks payload


class AnalyzeResponse(BaseModel):
    rating: str
    confidence: float
    rationale: str
    riskNotes: str
    winProbability: float  # extra transparency field (ignored by Java parser)


# ---------------------------------------------------------------------------
# ML model wrapper
# ---------------------------------------------------------------------------


class SkewModel:
    """Loads the trained XGBoost booster and scores live market states.

    Features the Java payload can't supply (GEX distances, liquidity, IV rank)
    are enriched from the newest local Schwab snapshot when available and left
    as NaN otherwise — XGBoost handles missing values natively.
    """

    def __init__(self) -> None:
        self.model = None
        self.columns: list[str] = []
        model_path = ARTIFACTS_DIR / "skew_ml_model.json"
        cols_path = ARTIFACTS_DIR / "feature_columns.json"
        if model_path.exists() and cols_path.exists():
            from xgboost import XGBClassifier

            self.model = XGBClassifier()
            self.model.load_model(model_path)
            self.columns = json.loads(cols_path.read_text())
            log.info("Loaded XGBoost model with %d features", len(self.columns))
        else:
            log.warning(
                "No trained model at %s — /analyze will use heuristic win probability. "
                "Run train_skew_model.py first.", model_path
            )

    # -- snapshot enrichment ------------------------------------------------

    def _latest_snapshot_features(self, ticker: str, spot: float) -> dict:
        clean = ticker.replace("$", "").upper()
        candidates = sorted(SNAPSHOT_DIR.glob(f"{clean}*_chain_*.json"))
        if not candidates:
            return {}
        try:
            import train_skew_model as tsm

            snaps = tsm.load_snapshots(SNAPSHOT_DIR, clean)
            if not snaps:
                return {}
            feats = tsm.snapshot_features(snaps[-1])
            return {
                k: feats.get(k)
                for k in (
                    "skew_95_105", "skew_curvature", "gex_net",
                    "dist_call_wall", "dist_put_wall", "dist_zero_flip",
                    "bid_ask_spread_pct", "volume_oi_ratio",
                    "vanna", "charm", "vomma",
                )
            }
        except Exception as exc:  # enrichment is best-effort, never fatal
            log.debug("Snapshot enrichment failed: %s", exc)
            return {}

    # -- scoring --------------------------------------------------------------

    def win_probability(self, req: AnalyzeRequest) -> float:
        ms, sig = req.marketState, req.skewSignal
        atm_iv = (ms.putIv + ms.callIv) / 2.0 or np.nan

        row = {c: np.nan for c in self.columns}
        row.update(
            {
                "atm_iv": atm_iv,
                "put_call_atm_skew": ms.skew,
                "spot_ret_1": sig.spotReturn or ms.spotReturn,
                "spot_ret_10": sig.spotReturn or ms.spotReturn,
                "skew_mom_1": sig.skewChange or ms.skewChange,
                "skew_mom_10": sig.skewChange or ms.skewChange,
                "option_type_flag": -1.0 if sig.optionType.lower() == "put" else 1.0,
            }
        )
        row.update({k: v for k, v in self._latest_snapshot_features(ms.ticker, ms.spotPrice).items()
                    if k in row and v is not None})
        if req.greeksProfile:
            for k in ("dist_call_wall", "dist_put_wall", "dist_zero_flip", "gex_net"):
                if k in req.greeksProfile and k in row:
                    row[k] = req.greeksProfile[k]

        if self.model is None:
            return self._heuristic_probability(req)

        X = np.array([[row[c] for c in self.columns]], dtype=float)
        return float(self.model.predict_proba(X)[0, 1])

    @staticmethod
    def _heuristic_probability(req: AnalyzeRequest) -> float:
        """Fallback score when no trained model exists: sigmoid over the
        divergence strength (spot move × skew confirmation)."""
        sig = req.skewSignal
        strength = abs(sig.spotReturn) * 50.0 + abs(sig.skewChange) * 50.0
        aligned = (
            (sig.signalType == "BEARISH_DIVERGENCE" and sig.optionType.lower() == "put")
            or (sig.signalType == "BULLISH_DIVERGENCE" and sig.optionType.lower() == "call")
        )
        raw = 1.0 / (1.0 + math.exp(-strength)) if aligned else 0.35
        return round(min(max(raw, 0.05), 0.75), 3)


SKEW_MODEL = SkewModel()

# ---------------------------------------------------------------------------
# Optional Gemini LLM (LangGraph nodes fall back to deterministic logic)
# ---------------------------------------------------------------------------


def _make_llm():
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        log.info("No GEMINI_API_KEY/GOOGLE_API_KEY — agents run in deterministic mode.")
        return None
    try:
        from langchain_google_genai import ChatGoogleGenerativeAI

        return ChatGoogleGenerativeAI(
            model=os.getenv("AGENT_LLM_MODEL", "gemini-1.5-flash"),
            temperature=0.2,
            google_api_key=api_key,
        )
    except Exception as exc:
        log.warning("Gemini init failed (%s) — deterministic mode.", exc)
        return None


LLM = _make_llm()


def _ask_llm(system: str, user: str) -> Optional[str]:
    if LLM is None:
        return None
    try:
        resp = LLM.invoke([("system", system), ("human", user)])
        return resp.content if isinstance(resp.content, str) else str(resp.content)
    except Exception as exc:
        log.warning("LLM call failed, using deterministic fallback: %s", exc)
        return None


# ---------------------------------------------------------------------------
# LangGraph 3-node workflow
# ---------------------------------------------------------------------------


class AgentState(TypedDict, total=False):
    request: AnalyzeRequest
    win_probability: float
    quant_view: str
    quant_score: float          # -1 (avoid) … +1 (strong take)
    macro_view: str
    macro_score: float
    rating: str
    confidence: float
    rationale: str
    risk_notes: str


def quant_analyst(state: AgentState) -> AgentState:
    """Node 1 — XGBoost win probability, valuation, and GEX resistance."""
    req = state["request"]
    p = SKEW_MODEL.win_probability(req)
    ms, sig = req.marketState, req.skewSignal

    # Deterministic quant score: model probability re-centered, penalized when
    # buying into rich IV (crush risk) on the side being purchased.
    entry_iv = ms.putIv if sig.optionType.lower() == "put" else ms.callIv
    iv_penalty = 0.15 if entry_iv > 0.35 else 0.0
    score = (p - 0.5) * 2.0 - iv_penalty

    summary = (
        f"Model win probability {p:.2f} for a long {sig.optionType.upper()} on "
        f"{sig.signalType} (spotReturn {sig.spotReturn:+.2%}, skewChange {sig.skewChange:+.2%}). "
        f"Entry-side IV {entry_iv:.1%}{' — elevated, vol-crush risk' if iv_penalty else ''}."
    )
    llm_view = _ask_llm(
        "You are a quantitative options analyst. In 2 sentences, assess this trade setup. "
        "Be specific about win probability, IV richness, and dealer gamma positioning.",
        f"{summary}\nSpot {ms.spotPrice:.2f}, put IV {ms.putIv:.1%}, call IV {ms.callIv:.1%}, "
        f"skew {ms.skew:+.4f}.",
    )
    return {
        "win_probability": p,
        "quant_score": max(-1.0, min(1.0, score)),
        "quant_view": llm_view or summary,
    }


def macro_analyst(state: AgentState) -> AgentState:
    """Node 2 — narrative sentiment from enriched news headlines."""
    req = state["request"]
    headlines = req.recentNews[:5]
    if not headlines:
        return {"macro_score": 0.0, "macro_view": "No recent news — macro-neutral stance."}

    text = " ".join(headlines).lower()
    bull = sum(text.count(w) for w in ("bullish", "beats", "rally", "upgrade", "somewhat-bullish", "growth"))
    bear = sum(text.count(w) for w in ("bearish", "misses", "selloff", "downgrade", "somewhat-bearish", "recession", "cut"))
    score = max(-1.0, min(1.0, (bull - bear) / max(bull + bear, 1)))

    # Sentiment should support the direction being bought.
    direction = -1.0 if req.skewSignal.optionType.lower() == "put" else 1.0
    aligned_score = score * direction

    summary = (
        f"News scan of {len(headlines)} headlines: {bull} bullish vs {bear} bearish cues "
        f"({'supports' if aligned_score >= 0 else 'conflicts with'} the {req.skewSignal.optionType} trade)."
    )
    llm_view = _ask_llm(
        "You are a macro and news-sentiment analyst for an options desk. In 2 sentences, "
        "summarize what these headlines imply for the proposed trade, flagging Fed/CPI/earnings risk.",
        f"Proposed: long {req.skewSignal.optionType} on {req.skewSignal.signalType}.\n"
        + "\n".join(headlines),
    )
    return {"macro_score": aligned_score, "macro_view": llm_view or summary}


RATING_LADDER = ["SELL", "UNDERWEIGHT", "HOLD", "OVERWEIGHT", "BUY"]


def risk_manager(state: AgentState) -> AgentState:
    """Node 3 — synthesizes Quant + Macro into the final structured decision."""
    req = state["request"]
    composite = 0.7 * state["quant_score"] + 0.3 * state["macro_score"]

    risk_notes = []
    if req.currentPositions >= 1:
        composite -= 0.4
        risk_notes.append(f"{req.currentPositions} position(s) already open — exposure limit pressure.")
    if state["win_probability"] < 0.45:
        risk_notes.append(f"Model win probability {state['win_probability']:.2f} is below coin-flip.")
    if abs(state["macro_score"]) > 0.5 and state["macro_score"] < 0:
        risk_notes.append("News narrative conflicts with trade direction.")

    if composite >= 0.45:
        rating = "BUY"
    elif composite >= 0.15:
        rating = "OVERWEIGHT"
    elif composite >= -0.15:
        rating = "HOLD"
    elif composite >= -0.45:
        rating = "UNDERWEIGHT"
    else:
        rating = "SELL"

    confidence = round(min(max(0.5 + abs(composite) / 2.0, 0.0), 1.0), 3)
    rationale = f"Quant: {state['quant_view']} Macro: {state['macro_view']}"

    llm_view = _ask_llm(
        "You are the risk & portfolio manager with final authority. Given the analyst views, "
        f"the composite score {composite:+.2f}, and the mechanical rating {rating}, write a "
        "2-sentence final rationale. Do NOT change the rating; explain it.",
        rationale,
    )
    return {
        "rating": rating,
        "confidence": confidence,
        "rationale": llm_view or rationale,
        "risk_notes": " ".join(risk_notes) or "No blocking risk factors identified.",
    }


def build_workflow():
    from langgraph.graph import END, START, StateGraph

    g = StateGraph(AgentState)
    g.add_node("quant_analyst", quant_analyst)
    g.add_node("macro_analyst", macro_analyst)
    g.add_node("risk_manager", risk_manager)
    # Quant and Macro fan out from START and join at the Risk Manager.
    g.add_edge(START, "quant_analyst")
    g.add_edge(START, "macro_analyst")
    g.add_edge("quant_analyst", "risk_manager")
    g.add_edge("macro_analyst", "risk_manager")
    g.add_edge("risk_manager", END)
    return g.compile()


try:
    WORKFLOW = build_workflow()
except ImportError:
    WORKFLOW = None
    log.warning("langgraph not installed — /analyze will run nodes sequentially.")


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------

app = FastAPI(title="Skew Engine TradingAgents", version="1.0.0")


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "model_loaded": SKEW_MODEL.model is not None,
        "llm_enabled": LLM is not None,
        "langgraph": WORKFLOW is not None,
    }


@app.post("/analyze", response_model=AnalyzeResponse)
def analyze(req: AnalyzeRequest) -> AnalyzeResponse:
    log.info(
        "Analyze: %s %s | spot=%.2f skew=%+.4f positions=%d",
        req.skewSignal.signalType, req.skewSignal.optionType,
        req.marketState.spotPrice, req.marketState.skew, req.currentPositions,
    )

    if WORKFLOW is not None:
        state = WORKFLOW.invoke({"request": req})
    else:  # graceful degradation without langgraph installed
        state: AgentState = {"request": req}
        state.update(quant_analyst(state))
        state.update(macro_analyst(state))
        state.update(risk_manager(state))

    resp = AnalyzeResponse(
        rating=state["rating"],
        confidence=state["confidence"],
        rationale=state["rationale"][:1900],
        riskNotes=state["risk_notes"][:900],
        winProbability=state["win_probability"],
    )
    log.info("Decision: %s (confidence %.2f, winProb %.2f)", resp.rating, resp.confidence, resp.winProbability)
    return resp


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
