package com.tzme.helpdesk.vo;

import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.domain.TicketEvent;
import com.tzme.helpdesk.domain.TicketRecord;
import lombok.Data;

import java.util.List;

@Data
public class TicketDetailVO {
    private Ticket ticket;
    private List<TicketEvent> events;
    private List<TicketRecord> records;
    private Asset asset;
    private List<String> actions;
}
