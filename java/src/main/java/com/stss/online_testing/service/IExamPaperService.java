package com.stss.online_testing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.stss.online_testing.dto.ExamGenerateReq;
import com.stss.online_testing.entity.ExamPaper;

public interface IExamPaperService extends IService<ExamPaper> {
    Long generateExam(ExamGenerateReq req, Long operatorId);
    // 获取学生端试卷
    com.stss.online_testing.dto.ExamPaperStudentVO getExamPaperForStudent(Long examId);
    com.stss.online_testing.dto.ExamPaperStudentVO getExamPaperForTeacher(Long examId, Long operatorId);
}
