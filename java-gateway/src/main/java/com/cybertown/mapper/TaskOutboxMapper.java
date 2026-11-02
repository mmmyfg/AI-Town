package com.cybertown.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cybertown.entity.TaskOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskOutboxMapper extends BaseMapper<TaskOutbox> {
    @Select("""
            SELECT * FROM task_outbox
            WHERE status IN ('PENDING', 'FAILED', 'SENDING') AND next_retry_at <= CURRENT_TIMESTAMP(3)
            ORDER BY id ASC LIMIT #{limit}
            """)
    List<TaskOutbox> selectPublishable(@Param("limit") int limit);

    @Update("""
            UPDATE task_outbox
            SET status = 'SENDING', next_retry_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)
            WHERE id = #{id}
              AND status IN ('PENDING', 'FAILED', 'SENDING')
              AND next_retry_at <= CURRENT_TIMESTAMP(3)
            """)
    int claim(@Param("id") long id);
}
