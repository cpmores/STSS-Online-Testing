package com.stss.online_testing.core.storage;

import java.util.Map;
import lombok.Data;

@Data
public class StorageCommand {
    private String action;
    private Map<String, Object> payload;
    private Long operatorId;
    private String operatorRole;
}
