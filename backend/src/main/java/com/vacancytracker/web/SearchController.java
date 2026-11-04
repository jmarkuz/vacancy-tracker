package com.vacancytracker.web;

import com.vacancytracker.vacancy.VacancySearchService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final VacancySearchService search;

    public SearchController(VacancySearchService search) {
        this.search = search;
    }

    /**
     * Ranked full-text search. Blank or symbol-only text gives an empty list. Unlike the vacancy list, an
     * out-of-range {@code limit} is clamped, not rejected: it only sizes a typeahead.
     */
    @GetMapping
    public List<VacancySearchService.Hit> search(@RequestParam(name = "q", defaultValue = "") String q,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return search.search(q, limit);
    }
}
