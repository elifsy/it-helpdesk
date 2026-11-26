package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDate;
import java.util.Map;
import java.time.LocalDateTime;

@Mapper
public interface TicketMapper {
    @org.apache.ibatis.annotations.Select("select count(*) from it_ticket where assignee_id=#{id} and status not in ('CLOSED','REJECTED','CANCELLED')")
    long countOpenAssigned(@Param("id") Long id);

    /** 同一人对同一设备的未结工单：用于阻止重复提报 */
    @org.apache.ibatis.annotations.Select("select * from it_ticket where creator_id=#{creatorId} and asset_no=#{assetNo} " +
            "and status not in ('CLOSED','REJECTED','CANCELLED') order by id desc limit 1")
    Ticket findOpenByCreatorAndAsset(@Param("creatorId") Long creatorId, @Param("assetNo") String assetNo);
    @org.apache.ibatis.annotations.Select("select * from it_ticket where no=#{no} for update")
    com.tzme.helpdesk.domain.Ticket findByNoForUpdate(@org.apache.ibatis.annotations.Param("no") String no);
    int insert(Ticket t);

    Ticket findByNo(@Param("no") String no);

    int update(Ticket t);

    List<Ticket> queueCounts(@Param("nos") List<String> nos);

    List<Ticket> list(@Param("status") String status,
                      @Param("open") Boolean open,
                      @Param("keyword") String keyword,
                      @Param("creatorId") Long creatorId,
                      @Param("assigneeId") Long assigneeId,
                      @Param("priority") String priority,
                      @Param("cat1") String cat1,
                      @Param("assetNo") String assetNo,
                      @Param("offset") int offset,
                      @Param("size") int size,
                      @Param("supplementDate") LocalDate supplementDate);

    long count(@Param("status") String status,
               @Param("open") Boolean open,
               @Param("keyword") String keyword,
               @Param("creatorId") Long creatorId,
               @Param("assigneeId") Long assigneeId,
               @Param("priority") String priority,
               @Param("cat1") String cat1,
               @Param("assetNo") String assetNo);

    List<Ticket> findTimeout(@Param("now") LocalDateTime now, @Param("before") LocalDateTime before, @Param("limit") int limit);

    List<Ticket> findConfirmationReminders(@Param("before") LocalDateTime before, @Param("limit") int limit);

    int markTimeoutReminder(@Param("no") String no, @Param("now") LocalDateTime now, @Param("before") LocalDateTime before);

    int markConfirmationReminder(@Param("no") String no, @Param("now") LocalDateTime now, @Param("before") LocalDateTime before);

    Map<String, Object> overview();

    List<Map<String, Object>> trend(@Param("since") java.time.LocalDateTime since);

    List<Map<String, Object>> categoryDist(@Param("since") java.time.LocalDateTime since);

    List<Map<String, Object>> engineerRank(@Param("since") java.time.LocalDateTime since);

    List<Map<String, Object>> problemAssets(@Param("limit") int limit);

    /** 更换资产时把未结工单转移到新设备 */
    @org.apache.ibatis.annotations.Update(
            "update it_ticket set asset_no = #{newNo} " +
            "where asset_no = #{oldNo} and status not in ('CLOSED','REJECTED','CANCELLED')")
    int transferAsset(@Param("oldNo") String oldNo, @Param("newNo") String newNo);

    /** 人员离职时把其名下未结工单转派给接手人 */
    @org.apache.ibatis.annotations.Update(
            "update it_ticket set assignee_id = #{toId}, assignee_name = #{toName}, updated_at = now() "
            + ", assigned_at = now() where assignee_id = #{fromId} and status not in ('CLOSED','REJECTED','CANCELLED')")
    int transferAssignee(@Param("fromId") Long fromId, @Param("toId") Long toId, @Param("toName") String toName);
}
