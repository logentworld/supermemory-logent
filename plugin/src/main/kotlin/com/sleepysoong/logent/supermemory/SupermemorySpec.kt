package com.sleepysoong.logent.supermemory

import com.sleepysoong.logent.plugin.ToolSpec
import com.sleepysoong.logent.plugin.integerProperty
import com.sleepysoong.logent.plugin.stringArrayProperty
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.toolSpec

/**
 * `memory` 도구의 모델 계약이에요.
 *
 * 이름·설명·required·속성 설명과 범위는 코어 `SupermemoryPlugin`이 선언하던 값과
 * 글자 단위로 같아요. 호스트는 이 스키마를 그대로 모델에 전달해요.
 */
internal object SupermemorySpec {
    const val TOOL_NAME = "memory"
    const val DEFAULT_RECALL_LIMIT = 8
    const val MAX_RECALL_LIMIT = 100

    val memory: ToolSpec = toolSpec(
        name = TOOL_NAME,
        description = "Supermemory에 오래 남길 기억을 저장하고 다시 불러와요. " +
            "save는 content를 기억으로 저장하고, recall은 query로 관련 기억과 문서 조각을 찾고, " +
            "context는 네임스페이스 프로필 요약을 읽고, forget은 ids에 적은 기억 id만 정확히 삭제해요. " +
            "forget은 의미 기반 일괄 삭제를 지원하지 않으니 먼저 recall로 id를 확인해 주세요. " +
            "사용자에 대한 사실·선호·결정처럼 다음 대화에도 남길 만한 내용만 짧게 저장해요.",
        required = listOf("action"),
    ) {
        stringProperty(
            name = "action",
            description = "수행할 작업이에요. save·recall·context·forget 중 하나예요.",
            enum = listOf("save", "recall", "context", "forget"),
        )
        stringProperty(
            name = "content",
            description = "save에서 저장할 내용이에요. 한 번에 한 주제로 짧게 적어 주세요.",
        )
        stringProperty(
            name = "query",
            description = "recall에서 찾을 질문이나 주제예요.",
        )
        stringArrayProperty(
            name = "ids",
            description = "forget에서 삭제할 기억 id 목록이에요. recall 결과의 id를 1~500개까지 적어 주세요.",
        )
        integerProperty(
            name = "limit",
            description = "recall에서 돌려받을 최대 결과 수예요. 기본값은 $DEFAULT_RECALL_LIMIT" + "개예요.",
            minimum = 1,
            maximum = MAX_RECALL_LIMIT,
        )
    }
}
