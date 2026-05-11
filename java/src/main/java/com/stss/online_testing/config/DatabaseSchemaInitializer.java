package com.stss.online_testing.config;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void ensureTablesExist() {
        createQuestionTable();
        createExamPaperTable();
        createExamPaperQuestionTable();
        createStudentExamRecordTable();
        createStudentExamAnswerTable();
        createExamRuntimeConfigTable();
        createActionLogTable();
    }

    private void createQuestionTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS question ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + "course_id BIGINT NOT NULL,"
                        + "type INT NOT NULL,"
                        + "stem TEXT NOT NULL,"
                        + "options JSON NULL,"
                        + "answer VARCHAR(255) NOT NULL,"
                        + "difficulty INT NOT NULL,"
                        + "knowledge_points JSON NULL,"
                        + "creator_id BIGINT NULL,"
                        + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "is_deleted TINYINT NOT NULL DEFAULT 0,"
                        + "INDEX idx_question_course_type (course_id, type),"
                        + "INDEX idx_question_creator (creator_id)"
                        + ")");
    }

    private void createExamPaperTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS exam_paper ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + "course_id BIGINT NOT NULL,"
                        + "title VARCHAR(255) NOT NULL,"
                        + "total_score INT NOT NULL,"
                        + "duration_mins INT NOT NULL,"
                        + "pass_score INT NOT NULL DEFAULT 60,"
                        + "status INT NOT NULL DEFAULT 0,"
                        + "creator_id BIGINT NULL,"
                        + "valid_start_time DATETIME NULL,"
                        + "valid_end_time DATETIME NULL,"
                        + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "is_deleted TINYINT NOT NULL DEFAULT 0,"
                        + "INDEX idx_exam_paper_course_creator (course_id, creator_id),"
                        + "INDEX idx_exam_paper_status (status)"
                        + ")");
    }

    private void createExamPaperQuestionTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS exam_paper_question ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + "paper_id BIGINT NOT NULL,"
                        + "question_id BIGINT NOT NULL,"
                        + "score INT NOT NULL,"
                        + "sort_order INT NOT NULL,"
                        + "UNIQUE KEY uk_paper_question (paper_id, question_id),"
                        + "INDEX idx_paper_sort (paper_id, sort_order)"
                        + ")");
    }

    private void createStudentExamRecordTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS student_exam_record ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + "exam_id BIGINT NOT NULL,"
                        + "student_id BIGINT NOT NULL,"
                        + "course_id BIGINT NULL,"
                        + "total_score INT NULL DEFAULT 0,"
                        + "status INT NOT NULL DEFAULT 0,"
                        + "submit_time DATETIME NULL,"
                        + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "is_deleted TINYINT NOT NULL DEFAULT 0,"
                        + "INDEX idx_record_exam_student (exam_id, student_id),"
                        + "INDEX idx_record_status_score (status, total_score)"
                        + ")");
    }

    private void createStudentExamAnswerTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS student_exam_answer ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + "record_id BIGINT NOT NULL,"
                        + "question_id BIGINT NOT NULL,"
                        + "student_answer VARCHAR(1024) NULL,"
                        + "is_correct TINYINT NULL,"
                        + "score INT NULL DEFAULT 0,"
                        + "UNIQUE KEY uk_record_question (record_id, question_id),"
                        + "INDEX idx_answer_question (question_id)"
                        + ")");
    }

    private void createExamRuntimeConfigTable() {
        // 运行时配置独立成扩展表，减少对既有主表结构的侵入。
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS exam_runtime_config ("
                        + "exam_id BIGINT PRIMARY KEY,"
                        + "allowed_attempts INT NOT NULL DEFAULT 1,"
                        + "score_visible TINYINT NOT NULL DEFAULT 0,"
                        + "answer_visible TINYINT NOT NULL DEFAULT 0,"
                        + "scoring_strategy VARCHAR(32) NOT NULL DEFAULT 'AUTO_GRADE',"
                        + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                        + ")");
    }

    private void createActionLogTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS action_log ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + "level INT NOT NULL DEFAULT 0,"
                        + "service VARCHAR(128) NOT NULL,"
                        + "operation_id INT NOT NULL DEFAULT 0,"
                        + "trace_id VARCHAR(128) NULL,"
                        + "user_id VARCHAR(64) NULL,"
                        + "message VARCHAR(255) NULL,"
                        + "method VARCHAR(16) NULL,"
                        + "path VARCHAR(255) NULL,"
                        + "status_code INT NULL,"
                        + "duration_ms BIGINT NULL,"
                        + "entity_type VARCHAR(64) NULL,"
                        + "entity_id VARCHAR(128) NULL,"
                        + "string_fields JSON NULL,"
                        + "int_fields JSON NULL,"
                        + "error_message TEXT NULL,"
                        + "stack_trace LONGTEXT NULL,"
                        + "grpc_delivered TINYINT NOT NULL DEFAULT 0,"
                        + "grpc_error VARCHAR(255) NULL,"
                        + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "INDEX idx_service_operation_time (service, operation_id, create_time),"
                        + "INDEX idx_trace_time (trace_id, create_time),"
                        + "INDEX idx_user_time (user_id, create_time)"
                        + ")");
    }
}
