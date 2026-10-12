-- Đồng hồ retry tách khỏi created_at. Redrive 1 message DEAD phải cho nó MỘT KHOẢNG RETRY MỚI;
-- nếu tính tuổi từ created_at thì message vừa redrive đã "quá hạn" sẵn và rơi lại vào DEAD ở lần lỗi đầu tiên
-- (chaos test đã bắt được: 3 email bị mất sau redrive).
ALTER TABLE outbox ADD COLUMN retry_started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
