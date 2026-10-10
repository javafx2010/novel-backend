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
 * 后台管理接口：EPUB 上传（自动解析入库）、书目列表与删除。
 * <p>
 * 生产环境建议开启 {@code app.admin.token}，或在网关 / 防火墙层面限制访问来源。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 上传 EPUB：解析后自动创建书目与章节 */
    @PostMapping(value = "/novels", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NovelSummary upload(@RequestPart("file") MultipartFile file,
                               @RequestParam("categoryId") Long categoryId,
                               @RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "tags", required = false) String tags) throws IOException {
        validateEpub(file);
        Novel novel = adminService.importEpub(file.getBytes(), file.getOriginalFilename(), categoryId, status, tags);
        return NovelSummary.of(novel);
    }

    /** 后台书目列表（按更新时间倒序） */
    @GetMapping("/novels")
    public List<NovelSummary> list() {
        return adminService.listNovels().stream().map(NovelSummary::of).toList();
    }

    /** 删除书目及其章节 */
    @DeleteMapping("/novels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminService.deleteNovel(id);
    }

    private void validateEpub(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("上传文件为空");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new BadRequestException("只支持 EPUB 格式（.epub）：" + name);
        }
    }
}
