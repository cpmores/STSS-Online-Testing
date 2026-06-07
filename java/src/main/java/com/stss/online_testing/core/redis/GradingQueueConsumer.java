package com.stss.online_testing.core.redis;

import com.stss.online_testing.core.proctor.ProctorFacade;
import com.stss.online_testing.core.proctor.ProctorFacade.AnswerPayload;
import com.google.protobuf.InvalidProtocolBufferException;
import com.stss.online_testing.proto.proctor.v1.Proctor.GradingTask;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "proctor.redis.enabled", havingValue = "true", matchIfMissing = false)
public class GradingQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GradingQueueConsumer.class);
    private static final String QUEUE_KEY = "grading:pending";
    private static final int IDLE_POLL_MILLIS = 500;

    private final StringRedisTemplate redisTemplate;
    private final ProctorFacade proctorFacade;
    private volatile boolean running = false;
    private Thread consumerThread;

    public GradingQueueConsumer(
            StringRedisTemplate redisTemplate,
            ProctorFacade proctorFacade) {
        this.redisTemplate = redisTemplate;
        this.proctorFacade = proctorFacade;
    }

    @Override
    public void start() {
        running = true;
        consumerThread = new Thread(this::consumeLoop, "grading-queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("Grading queue consumer started");
    }

    @Override
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        log.info("Grading queue consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void consumeLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                String taskJson = redisTemplate.opsForList().leftPop(QUEUE_KEY);
                if (taskJson == null) {
                    Thread.sleep(IDLE_POLL_MILLIS);
                    continue;
                }

                GradingTask task = parseTask(taskJson);
                processGradingTask(task);

            } catch (InvalidProtocolBufferException e) {
                log.error("Failed to parse grading task from Redis queue", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error consuming grading queue", e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void processGradingTask(GradingTask task) {
        Long examId = task.getExamId();
        Long studentId = task.getStudentId();
        Long recordId = task.getRecordId();

        try {
            List<AnswerPayload> answers = loadAnswersFromRedis(examId, studentId);

            proctorFacade.gradeExamFromProctor(examId, studentId, recordId, answers);
            log.info("Grading task completed: examId={}, studentId={}, recordId={}",
                    examId, studentId, recordId);

        } catch (Exception e) {
            log.error("Failed to process grading task: examId={}, studentId={}, recordId={}",
                    examId, studentId, recordId, e);
        }
    }

    private GradingTask parseTask(String taskPayload) throws InvalidProtocolBufferException {
        try {
            return GradingTask.parseFrom(Base64.getDecoder().decode(taskPayload));
        } catch (IllegalArgumentException ignored) {
            return GradingTask.parseFrom(taskPayload.getBytes());
        }
    }

    private List<AnswerPayload> loadAnswersFromRedis(Long examId, Long studentId) {
        String key = "exam:" + examId + ":answers:" + studentId;
        Map<Object, Object> answerMap = redisTemplate.opsForHash().entries(key);
        List<AnswerPayload> payloads = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : answerMap.entrySet()) {
            AnswerPayload payload = new AnswerPayload();
            payload.setQuestionId(Long.parseLong(String.valueOf(entry.getKey())));
            payload.setStudentAnswer(String.valueOf(entry.getValue()));
            payloads.add(payload);
        }
        return payloads;
    }
}
