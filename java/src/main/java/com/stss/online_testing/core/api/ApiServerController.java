package com.stss.online_testing.core.api;

import com.stss.online_testing.common.Result;
import com.stss.online_testing.core.config.ConfigManager;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ot/v1")
public class ApiServerController {

    private final ConfigManager configManager;

    public ApiServerController(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 文档中的 API Server 统一入口：
     * 解析 action + data，交给 Config Manager 分发，再统一构造响应体。
     */
    @PostMapping("/actions")
    public Result<Object> dispatch(@RequestBody ApiActionProtocol.Request request) {
        ApiActionProtocol.DispatchResult result = configManager.dispatch(request);
        return Result.success(result.getMessage(), result.getData());
    }

    /**
     * 文件上传与下载不适合直接塞进 JSON 包体，这里保留为 API Server 的专用适配入口。
     */
    @PostMapping(
            value = "/actions/question-bank/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> importQuestions(
            @RequestParam("file") MultipartFile file,
            @RequestParam("teacherId") Long teacherId) {
        ApiActionProtocol.DispatchResult result = configManager.importQuestions(file, teacherId);
        return Result.success(result.getMessage(), result.getData());
    }

    @PostMapping("/actions/exams/export")
    public void exportExamScores(
            @RequestBody Map<String, Object> request,
            HttpServletResponse response) {
        configManager.exportExamScores(request, response);
    }
}
