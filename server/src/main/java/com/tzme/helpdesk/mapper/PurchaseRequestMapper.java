package com.tzme.helpdesk.mapper;

import com.tzme.helpdesk.domain.PurchaseRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PurchaseRequestMapper {

    int insert(PurchaseRequest r);

    int update(PurchaseRequest r);

    @org.apache.ibatis.annotations.Select("select * from it_purchase_request where id=#{id} for update")
    PurchaseRequest findByIdForUpdate(@Param("id") Long id);

    PurchaseRequest findById(@Param("id") Long id);

    PurchaseRequest findByNo(@Param("no") String no);

    List<PurchaseRequest> list(@Param("status") String status,
                               @Param("applicant") String applicant,
                               @Param("mine") boolean mine);

    long countPending();

    /** 已发起钉钉审批、仍在待审批的申请（轮询用） */
    @org.apache.ibatis.annotations.Select("select * from it_purchase_request where status='PENDING' " +
            "and ding_instance_id is not null and ding_instance_id <> '' order by id asc")
    List<PurchaseRequest> listDingPending();

}
