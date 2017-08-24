package com.odysseusinc.arachne.executionengine.service.impl;

import com.google.common.io.Files;
import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisRequestDTO;
import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisRequestStatusDTO;
import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisRequestTypeDTO;
import com.odysseusinc.arachne.executionengine.service.AnalysisService;
import com.odysseusinc.arachne.executionengine.service.CdmMetadataService;
import com.odysseusinc.arachne.executionengine.service.RuntimeService;
import com.odysseusinc.arachne.executionengine.service.SQLService;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class AnalysisServiceImpl implements AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisServiceImpl.class);

    private final SQLService sqlService;
    private final RuntimeService runtimeService;
    private final ThreadPoolTaskExecutor threadPoolTaskExecutor;
    private final CdmMetadataService cdmMetadataService;

    @Autowired
    public AnalysisServiceImpl(SQLService sqlService,
                               RuntimeService runtimeService,
                               @Qualifier("analysisTaskExecutor") ThreadPoolTaskExecutor threadPoolTaskExecutor,
                               CdmMetadataService cdmMetadataService) {

        this.sqlService = sqlService;
        this.runtimeService = runtimeService;
        this.threadPoolTaskExecutor = threadPoolTaskExecutor;
        this.cdmMetadataService = cdmMetadataService;
    }

    @Override
    public AnalysisRequestStatusDTO analyze(AnalysisRequestDTO analysis, File analysisDir, Boolean compressedResult, Long chunkSize) {

        Validate.notNull(analysis, "analysis can't be null");

        try {
            cdmMetadataService.extractMetadata(analysis, analysisDir);
        } catch (SQLException | IOException e) {
            logger.info("Failed to collect CDM metadata. " + e);
        }
        String executableFileName = analysis.getExecutableFileName();
        String fileExtension = Files.getFileExtension(executableFileName).toLowerCase();
        AnalysisRequestTypeDTO status;
        switch (fileExtension) {
            case "sql": {
                sqlService.analyze(analysis, analysisDir, compressedResult, chunkSize);
                logger.info("analysis with id={} started in SQL Service", analysis.getId());
                status = AnalysisRequestTypeDTO.SQL;
                break;
            }

            case "r": {
                runtimeService.analyze(analysis, analysisDir, compressedResult, chunkSize);
                logger.info("analysis with id={} started in R Runtime Service", analysis.getId());
                status = AnalysisRequestTypeDTO.R;
                break;
            }

            default: {
                logger.info("analysis with id={} is not recognized. Skipping", analysis.getId());
                status = AnalysisRequestTypeDTO.NOT_RECOGNIZED;
            }
        }
        return new AnalysisRequestStatusDTO(analysis.getId(), status);
    }

    @Override
    public int activeTasks() {

        return threadPoolTaskExecutor.getActiveCount();
    }
}
