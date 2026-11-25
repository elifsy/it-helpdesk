package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface AssetMapper {
    int insert(Asset a);

    Asset findByNo(@Param("no") String no);

    @org.apache.ibatis.annotations.Select("select no from it_asset where erp_no=#{erpNo}")
    String findNoByErpNo(@Param("erpNo") String erpNo);

    @org.apache.ibatis.annotations.Select("select * from it_asset where no=#{no} for update")
    Asset findByNoForUpdate(@Param("no") String no);

    int update(Asset a);

    @org.apache.ibatis.annotations.Update("update it_asset set erp_no=#{erpNo},name=#{name},model=#{model},category=#{category},sn=#{sn},location=#{location},buy_date=#{buyDate},warranty=#{warranty},warranty_end=#{warrantyEnd},status=#{status},remark=#{remark},icon=#{icon},updated_at=now() where no=#{no}")
    int updateDetails(Asset asset);

    List<Asset> list(@Param("status") String status,
                     @Param("category") String category,
                     @Param("keyword") String keyword);

    List<Asset> page(@Param("status") String status, @Param("category") String category,
                    @Param("keyword") String keyword, @Param("offset") long offset, @Param("size") int size);

    long count(@Param("status") String status, @Param("category") String category, @Param("keyword") String keyword);

    long countByStatus(@Param("status") String status);

    long countAll();

    @org.apache.ibatis.annotations.Select("select * from it_asset where status='借出中' and borrower_id=#{borrowerId} "
            + "and borrow_due<=#{until} order by borrow_due,no")
    List<Asset> borrowDueForUser(@Param("borrowerId") Long borrowerId, @Param("until") LocalDate until);

    @org.apache.ibatis.annotations.Select("select a.* from it_asset a join sys_user u on u.id=a.borrower_id and u.status='ACTIVE' "
            + "where a.status='借出中' and ((a.borrow_due<#{today} and a.borrow_overdue_reminded_at is null) "
            + "or (a.borrow_due between #{today} and #{until} and a.borrow_soon_reminded_at is null)) "
            + "order by a.borrow_due,a.no limit #{limit}")
    List<Asset> findBorrowReminderCandidates(@Param("today") LocalDate today, @Param("until") LocalDate until, @Param("limit") int limit);

    @org.apache.ibatis.annotations.Update("update it_asset set borrow_soon_reminded_at=#{now},updated_at=updated_at "
            + "where no=#{no} and borrower_id=#{borrowerId} and borrow_due=#{due} and status='借出中' and borrow_soon_reminded_at is null")
    int markBorrowSoon(@Param("no") String no, @Param("borrowerId") Long borrowerId, @Param("due") LocalDate due, @Param("now") LocalDateTime now);

    @org.apache.ibatis.annotations.Update("update it_asset set borrow_overdue_reminded_at=#{now},updated_at=updated_at "
            + "where no=#{no} and borrower_id=#{borrowerId} and borrow_due=#{due} and status='借出中' and borrow_overdue_reminded_at is null")
    int markBorrowOverdue(@Param("no") String no, @Param("borrowerId") Long borrowerId, @Param("due") LocalDate due, @Param("now") LocalDateTime now);
}
