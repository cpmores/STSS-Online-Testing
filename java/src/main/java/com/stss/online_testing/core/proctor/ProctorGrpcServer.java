package com.stss.online_testing.core.proctor;

import com.stss.online_testing.config.ProctorGrpcProperties;
import com.stss.online_testing.proto.proctor.v1.ProctorServiceGrpc;
import com.stss.online_testing.proto.proctor.v1.Proctor.CommitExamRequest;
import com.stss.online_testing.proto.proctor.v1.Proctor.CommitExamResponse;
import com.stss.online_testing.proto.proctor.v1.Proctor.AnswerEntry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "proctor.grpc.enabled", havingValue = "true", matchIfMissing = true)
public class ProctorGrpcServer {

    private static final Logger log = LoggerFactory.getLogger(ProctorGrpcServer.class);

    private final ProctorGrpcProperties properties;
    private final ProctorFacade proctorFacade;
    private Server server;

    public ProctorGrpcServer(ProctorGrpcProperties properties, ProctorFacade proctorFacade) {
        this.properties = properties;
        this.proctorFacade = proctorFacade;
    }

    @PostConstruct
    public void start() throws IOException {
        int port = properties.getGrpc().getPort();
        server = ServerBuilder.forPort(port)
                .addService(new ProctorServiceImpl())
                .build()
                .start();
        log.info("Proctor gRPC server started on port {}", port);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("Proctor gRPC server shutdown");
        }
    }

    private class ProctorServiceImpl extends ProctorServiceGrpc.ProctorServiceImplBase {

        @Override
        public void commitExam(CommitExamRequest request, StreamObserver<CommitExamResponse> responseObserver) {
            try {
                Long examId = request.getExamId();
                Long studentId = request.getStudentId();
                Long recordId = request.getRecordId();
                List<ProctorFacade.AnswerPayload> answers = new ArrayList<>();
                for (AnswerEntry entry : request.getAnswersList()) {
                    ProctorFacade.AnswerPayload payload = new ProctorFacade.AnswerPayload();
                    payload.setQuestionId(entry.getQuestionId());
                    payload.setStudentAnswer(entry.getStudentAnswer());
                    answers.add(payload);
                }

                Map<String, Object> result =
                        proctorFacade.gradeExamFromProctor(examId, studentId, recordId, answers);

                int totalScore = (Integer) result.get("totalScore");

                CommitExamResponse response = CommitExamResponse.newBuilder()
                        .setSuccess(true)
                        .setTotalScore(totalScore)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();

                log.info("gRPC commitExam success: examId={}, studentId={}, recordId={}, score={}",
                        examId, studentId, recordId, totalScore);

            } catch (Exception e) {
                log.error("gRPC commitExam failed: {}", e.getMessage(), e);
                CommitExamResponse response = CommitExamResponse.newBuilder()
                        .setSuccess(false)
                        .setErrorCode(500)
                        .setErrorMsg(e.getMessage())
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        }
    }
}
