package com.cybertown.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper {
    @Insert("""
            INSERT IGNORE INTO conversations(conversation_id, player_id, npc_id)
            VALUES(#{conversationId}, #{playerId}, #{npcId})
            """)
    int insertIfAbsent(@Param("conversationId") String conversationId,
                       @Param("playerId") String playerId,
                       @Param("npcId") String npcId);

    @Update("""
            UPDATE conversations SET last_sequence = LAST_INSERT_ID(last_sequence + 1)
            WHERE conversation_id = #{conversationId}
            """)
    int incrementSequence(@Param("conversationId") String conversationId);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    default long nextSequence(String conversationId) {
        if (incrementSequence(conversationId) != 1) {
            throw new IllegalStateException("Conversation not found: " + conversationId);
        }
        return lastInsertId();
    }

    @Insert("""
            INSERT IGNORE INTO conversation_messages
              (conversation_id, task_id, player_id, npc_id, sequence_no, role, content)
            VALUES
              (#{conversationId}, #{taskId}, #{playerId}, #{npcId}, #{sequenceNo}, #{role}, #{content})
            """)
    int insertMessage(@Param("conversationId") String conversationId,
                      @Param("taskId") String taskId,
                      @Param("playerId") String playerId,
                      @Param("npcId") String npcId,
                      @Param("sequenceNo") long sequenceNo,
                      @Param("role") String role,
                      @Param("content") String content);
}
