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

    /**
     * Uploads an EPUB: parses it and creates the novel with its chapters.
     * <p>
     * The target category is {@code categoryId}, else {@code categoryName} (created when it does not exist),
     * else {@code app.upload.default-category-name}.
     * <p>
     * {@code language} is the language of this edition ({@code en} / {@code zh_CN}); when omitted the EPUB
     * {@code dc:language} applies, then {@code app.i18n.default-locale}. Uploading the same work in another
     * language creates a second edition instead of overwriting the first one; both share a version group.
     * {@code versionGroup} links the upload to an existing work explicitly (generated when omitted).
     * <p>
     * Categories are per language: {@code categoryName} / {@code categoryDescription} create or update the
     * category of the language of this edition, and {@code categoryId} must belong to that language too.
     */
    @PostMapping(value = "/novels", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NovelSummary upload(@RequestPart("file") MultipartFile file,
                               @RequestParam(value = "categoryId", required = false) Long categoryId,
                               @RequestParam(value = "categoryName", required = false) String categoryName,
                               @RequestParam(value = "categoryDescription", required = false) String categoryDescription,
                               @RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "tags", required = false) String tags,
                               @RequestParam(value = "language", required = false) String language,
                               @RequestParam(value = "versionGroup", required = false) String versionGroup)
            throws IOException {
        validateEpub(file);
        Novel novel = adminService.upload(file.getBytes(), file.getOriginalFilename(), categoryId, categoryName,
                categoryDescription, status, tags, language, versionGroup);
        return NovelSummary.of(novel);
    }

    /** Admin novel list (newest update first); {@code language} restricts it to one language version */
    @GetMapping("/novels")
    public List<NovelSummary> list(@RequestParam(required = false) String language) {
        return adminService.listNovels(language).stream().map(NovelSummary::of).toList();
    }

    /** Deletes a novel and its chapters */
    @DeleteMapping("/novels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminService.deleteNovel(id);
    }

    private void validateEpub(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("error.upload.emptyFile");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new BadRequestException("error.upload.onlyEpub", name);
        }
    }
}
