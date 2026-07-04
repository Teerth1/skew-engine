package com.skew.engine.service;

import com.skew.engine.domain.NewsArticle;
import com.skew.engine.repository.NewsArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class NewsServicePITTests {

    private NewsArticleRepository repository;
    private NewsService newsService;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(NewsArticleRepository.class);
        newsService = new NewsService(repository);
    }

    @Test
    void getRecentNewsAsOfEnforcesPointInTimeConstraints() {
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 4, 12, 0, 0);
        String symbol = "AAPL";

        List<NewsArticle> mockArticles = List.of(
                new NewsArticle(symbol, "Title 1", "Source", "url", asOf.minusHours(1), "Summary", "Provider", "Neutral")
        );

        when(repository.findBySymbolAndPublishedAtLessThanEqualOrderByPublishedAtDesc(
                eq(symbol), eq(asOf), any(Pageable.class)))
                .thenReturn(mockArticles);

        List<NewsArticle> result = newsService.getRecentNewsAsOf(symbol, asOf);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Title 1");
    }
}
