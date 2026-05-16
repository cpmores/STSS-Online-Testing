package com.stss.online_testing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.stss.online_testing.dto.ExamSubmitReq;
import com.stss.online_testing.entity.StudentExamRecord;

public interface IStudentExamRecordService extends IService<StudentExamRecord> {
    /**
     * 自动评分并交卷
     */
    StudentExamRecord calculateAndSubmit(ExamSubmitReq req);

    // 获取试卷统计分析报告
    com.stss.online_testing.dto.ExamStatsVO getExamStats(Long examId);
}