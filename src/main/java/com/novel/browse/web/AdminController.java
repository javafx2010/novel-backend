package com.novel.browse.web;

import com.novel.browse.domain.Novel;
import com.novel.browse.service.AdminService;
import com.novel.browse.service.BadRequestException;
import com.novel.browse.web.dto.NovelSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Admin APIs: EPUB upload (parsed and stored automatically), novel listing and deletion.
 * <p>
 * In production, enable {@code app.admin.token} or restrict access at the gateway / firewall level.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** Uploads an EPUB: parses it and creates the novel with its chapters */
    @PostMapping(value = "/novels", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NovelSummary upload(@RequestPart("file") MultipartFile file,
                               @RequestParam("categoryId") Long categoryId,
                               @RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "tags", required = false) String tags) throws IOException {
        validateEpub(file);
        Novel novel = adminService.upload(file.getBytes(), file.getOriginalFilename(), categoryId, status, tags);
        return NovelSummary.of(novel);
    }

    /** Admin novel list (newest update first) */
    @GetMapping("/novels")
    public List<NovelSummary> list() {
        return adminService.listNovels().stream().map(NovelSummary::of).toList();
    }

    /** Deletes a novel and its chapters */
    @DeleteMapping("/novels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminService.deleteNovel(id);
    }

    private void validateEpub(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("The uploaded file is empty");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new BadRequestException("Only EPUB files (.epub) are supported: " + name);
        }
    }
}
