package com.planify;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Embeddable @Getter @Setter
public class RubricCriterion {
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false) private int points;
    public RubricCriterion copy() { RubricCriterion c = new RubricCriterion(); c.name = name; c.points = points; return c; }
}
