package com.novel.browse.web;

import com.novel.browse.domain.User;
import com.novel.browse.service.NotFoundException;
import com.novel.browse.service.ProgressService;
import com.novel.browse.web.dto.PageResult;
import com.novel.browse.web.dto.ProgressVo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading progress of the logged in reader, one row per book
 */
@RestController
@RequestMapping("/me/progress")
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping
    public PageResult<ProgressVo> list(@CurrentUser User user,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return progressService.list(user, page, size);
    }

    @GetMapping("/{novelId}")
    public ProgressVo get(@CurrentUser User user, @PathVariable Long novelId) {
        ProgressVo progress = progressService.get(user, novelId);
        if (progress == null) {
            throw new NotFoundException("No reading progress for novel: " + novelId);
        }
        return progress;
    }

    @PutMapping("/{novelId}")
    public ProgressVo save(@CurrentUser User user, @PathVariable Long novelId,
                           @RequestBody ProgressRequest body) {
        if (body == null || body.chapterNo() == null) {
            throw new IllegalArgumentException("chapterNo is required");
        }
        return progressService.save(user, novelId, body.chapterNo());
    }

    @DeleteMapping("/{novelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@CurrentUser User user, @PathVariable Long novelId) {
        progressService.remove(user, novelId);
    }

    /**
     * Update request: the chapter the reader stopped at
     */
    public record ProgressRequest(Integer chapterNo) {
    }
}
