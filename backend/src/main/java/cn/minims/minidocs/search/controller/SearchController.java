package cn.minims.minidocs.search.controller;

import cn.minims.minidocs.common.api.ApiResponse;
import cn.minims.minidocs.common.api.PageResult;
import cn.minims.minidocs.common.context.UserContext;
import cn.minims.minidocs.search.service.SearchService;
import cn.minims.minidocs.search.service.SearchService.SearchHit;
import cn.minims.minidocs.search.service.SearchService.SuggestItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "全局搜索")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "L1 联想（允许匿名）")
    @GetMapping("/suggest")
    public ApiResponse<Map<String, List<SuggestItem>>> suggest(@RequestParam(required = false) String q) {
        return ApiResponse.ok(searchService.suggest(q, UserContext.get()));
    }

    @Operation(summary = "L2 全文检索（允许匿名）")
    @GetMapping
    public ApiResponse<PageResult<SearchHit>> search(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) Long kbId,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(searchService.search(q, kbId, UserContext.get(), page, size));
    }
}
