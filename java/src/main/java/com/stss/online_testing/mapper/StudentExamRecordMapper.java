package com.stss.online_testing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stss.online_testing.entity.StudentExamRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Map;

@Mapper
public interface StudentExamRecordMapper extends BaseMapper<StudentExamRecord> {

    /**
     * 聚合查询试卷的统计信息
     * 返回 Map 方便在 Service 层处理空值情况
     */
    @Select("SELECT " +
            "COUNT(id) as attendCount, " +
            "MAX(total_score) as maxScore, " +
            "MIN(total_score) as minScore, " +
            "AVG(total_score) as avgScore " +
            "FROM student_exam_record " +
            "WHERE exam_id = #{examId} AND status = 1 AND is_deleted = 0")
    Map<String, Object> getExamStatistics(@Param("examId") Long examId);
    
    /**
     * 查询某张试卷及格的人数
     */
    @Select("SELECT COUNT(id) FROM student_exam_record " +
            "WHERE exam_id = #{examId} AND total_score >= #{passScore} AND status = 1 AND is_deleted = 0")
    Integer countPassStudents(@Param("examId") Long examId, @Param("passScore") Integer passScore);
}