package com.planify;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

@Embeddable @Getter @Setter
public class DevelopmentRecord {
    @Column(length = 500) private String aiTools;
    @Column(length = 10000) private String prompts;
    @Column(length = 10000) private String modifications;
    @Column(length = 10000) private String verification;
    public DevelopmentRecord copy() {
        DevelopmentRecord d = new DevelopmentRecord(); d.aiTools = aiTools; d.prompts = prompts;
        d.modifications = modifications; d.verification = verification; return d;
    }
}
