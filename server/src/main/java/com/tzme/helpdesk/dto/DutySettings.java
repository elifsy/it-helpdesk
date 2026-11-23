package com.tzme.helpdesk.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DutySettings(
        @NotNull @Size(max = 30) List<@NotNull @Valid Member> members,
        @NotNull @Size(max = 48) String hotline,
        @NotNull @Size(max = 100) String hotlineNote) {

    public record Member(@NotBlank @Size(max = 32) String position,
                         @NotBlank @Size(max = 64) String name, @Positive Long userId) {
        public Member(String position, String name) { this(position, name, null); }
    }

    public static DutySettings defaults() {
        return new DutySettings(List.of(new Member("服务台", "王敏"), new Member("终端", "李强"),
                new Member("系统", "孙鹏")), "8300", "系统故障兜底");
    }
}
