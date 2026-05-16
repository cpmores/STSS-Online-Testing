package com.stss.online_testing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stss.online_testing.entity.Question;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> 
{
    // 继承 BaseMapper 后，自带 insert, deleteById, updateById, selectById 等方法
    /**
     * 随机抽取指定数量的题目 ID
     * @param courseId 课程ID
     * @param type 题型 (1-单选, 2-是非)
     * @param limit 抽取的数量
     * @return 题目 ID 列表
     */
    @Select("SELECT id FROM question WHERE course_id = #{courseId} AND type = #{type} AND is_deleted = 0 ORDER BY RAND() LIMIT #{limit}")
    List<Long> getRandomQuestionIds(@Param("courseId") Long courseId, @Param("type") Integer type, @Param("limit") Integer limit);
}