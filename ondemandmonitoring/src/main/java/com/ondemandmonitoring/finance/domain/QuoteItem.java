package com.ondemandmonitoring.finance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.enums.QuoteItemType;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "quote_items", indexes = @Index(name = "ix_quote_items_quote", columnList = "quote_id"))
@Getter @Setter
public class QuoteItem extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_checklist_item_id")
    private OrderChecklistItem orderChecklistItem;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuoteItemType type;
    @Column(nullable = false, length = 500)
    private String description;
    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 0)
    private BigDecimal unitPrice;
    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
