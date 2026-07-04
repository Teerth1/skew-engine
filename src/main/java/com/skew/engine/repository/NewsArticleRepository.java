package com.skew.engine.repository;

import com.skew.engine.domain.NewsArticle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    /** Most-recent articles for a ticker, newest first. */
    List<NewsArticle> findBySymbolOrderByPublishedAtDesc(String symbol, Pageable pageable);

    /** Check for duplicates (title + symbol) to avoid re-persisting the same article. */
    boolean existsBySymbolAndTitle(String symbol, String title);

    /** Articles newer than a given timestamp for freshness checks. */
    List<NewsArticle> findBySymbolAndPublishedAtAfterOrderByPublishedAtDesc(
            String symbol, LocalDateTime after);

    /** PIT (Point-In-Time) query to retrieve articles strictly before or at asOf. */
    List<NewsArticle> findBySymbolAndPublishedAtLessThanEqualOrderByPublishedAtDesc(
            String symbol, LocalDateTime asOf, Pageable pageable);
}
