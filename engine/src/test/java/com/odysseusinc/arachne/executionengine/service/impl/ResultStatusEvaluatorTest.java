/*
 *
 * Copyright 2020 Odysseus Data Services, inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Company: Odysseus Data Services, Inc.
 * Product Owner/Architecture: Gregory Klebanov
 * Authors: Alex Cumarav, Vitaly Koulakov, Yaroslav Molodkov
 * Created: July 27, 2020
 *
 */

package com.odysseusinc.arachne.executionengine.service.impl;

import com.odysseusinc.arachne.execution_engine_common.api.v1.dto.AnalysisResultStatusDTO;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
public class ResultStatusEvaluatorTest {

    private ResultStatusEvaluator resultStatusEvaluator;

    @BeforeEach
    public void setUp() {

        resultStatusEvaluator = new ResultStatusEvaluator();
    }

    @Test
    public void shouldFailOnBadArgument() {

        assertThrows(NullPointerException.class, () -> resultStatusEvaluator.evaluateResultStatus(null));
    }

    @Test
    public void shouldPassAnalysesWithZeroExitCodeAndStdout() {

        final AnalysisResultStatusDTO analysisResult = resultStatusEvaluator.evaluateResultStatus(new RuntimeFinishState(0, "OK"));
        assertThat(analysisResult).isEqualTo(AnalysisResultStatusDTO.EXECUTED);
    }

    @Test
    public void shouldFailAnalysesWithNonZeroExitCode() {

        final AnalysisResultStatusDTO analysisResult = resultStatusEvaluator.evaluateResultStatus(new RuntimeFinishState(-1, "OK"));
        assertThat(analysisResult).isEqualTo(AnalysisResultStatusDTO.FAILED);
    }

    @Test
    public void shouldFailOnEmptyStdout() {

        final AnalysisResultStatusDTO analysisResult = resultStatusEvaluator.evaluateResultStatus(new RuntimeFinishState(0, StringUtils.EMPTY));
        assertThat(analysisResult).isEqualTo(AnalysisResultStatusDTO.FAILED);
    }
}