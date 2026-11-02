package com.cybertown.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cybertown.entity.PlayerNpcRelation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlayerNpcRelationMapper extends BaseMapper<PlayerNpcRelation> {
    @Insert("""
            INSERT IGNORE INTO player_npc_relations(player_id, npc_id, affinity, mood)
            VALUES(#{playerId}, #{npcId}, 50, #{mood})
            """)
    int insertIfAbsent(@Param("playerId") String playerId,
                       @Param("npcId") String npcId,
                       @Param("mood") String mood);

    @Update("""
            UPDATE player_npc_relations
            SET affinity = LEAST(100, GREATEST(0, affinity + #{delta})),
                mood = #{mood}, version = version + 1
            WHERE player_id = #{playerId} AND npc_id = #{npcId}
            """)
    int applyResult(@Param("playerId") String playerId,
                    @Param("npcId") String npcId,
                    @Param("delta") int delta,
                    @Param("mood") String mood);

    @Select("""
            SELECT * FROM player_npc_relations
            WHERE player_id = #{playerId} AND npc_id = #{npcId} LIMIT 1
            """)
    PlayerNpcRelation selectRelation(@Param("playerId") String playerId, @Param("npcId") String npcId);
}
