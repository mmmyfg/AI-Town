package com.cybertown.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cybertown.entity.ChatTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatTaskMapper extends BaseMapper<ChatTask> {
    @Select("SELECT * FROM chat_tasks WHERE task_id = #{taskId} LIMIT 1")
    ChatTask selectByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM chat_tasks WHERE task_id = #{taskId} LIMIT 1 FOR UPDATE")
    ChatTask selectByTaskIdForUpdate(@Param("taskId") String taskId);

    @Select("""
            SELECT task_id FROM chat_tasks
            WHERE status IN ('PENDING', 'PROCESSING', 'WAITING_RETRY', 'MODEL_DONE')
              AND updated_at < #{deadline}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<String> selectStaleTaskIds(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);
}
