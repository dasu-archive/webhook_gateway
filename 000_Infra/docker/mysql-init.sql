-- 게이트웨이와 소비자 서비스가 같은 인스턴스의 다른 스키마를 쓴다.
-- 이 스크립트는 데이터 볼륨이 비어 있을 때 한 번만 실행된다.
CREATE DATABASE IF NOT EXISTS webhook_gateway
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS webhook_service
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
