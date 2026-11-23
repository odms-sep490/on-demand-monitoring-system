package com.ondemandmonitoring.conversation;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderConversationRepository extends JpaRepository<OrderConversationSession,String> {}
