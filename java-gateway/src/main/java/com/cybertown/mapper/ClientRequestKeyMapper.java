package com.cybertown.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ClientRequestKeyMapper {
    @Insert("""
            INSERT IGNORE INTO client_request_keys
              (player_id, client_request_id, task_id, request_fingerprint)
            VALUES (#{playerId}, #{requestId}, #{taskId}, #{fingerprint})
            """)
    int insertIfAbsent(@Param("playerId") String playerId,
                       @Param("requestId") String requestId,
                       @Param("taskId") String taskId,
                       @Param("fingerprint") String fingerprint);

    @Select("""
            SELECT task_id FROM client_request_keys
            WHERE player_id = #{playerId} AND client_request_id = #{requestId}
            LIMIT 1
            """)
    String selectTaskId(@Param("playerId") String playerId,
                        @Param("requestId") String requestId);

    @Select("""
            SELECT request_fingerprint FROM client_request_keys
            WHERE player_id = #{playerId} AND client_request_id = #{requestId}
            LIMIT 1
            """)
    String selectFingerprint(@Param("playerId") String playerId,
                             @Param("requestId") String requestId);
}
