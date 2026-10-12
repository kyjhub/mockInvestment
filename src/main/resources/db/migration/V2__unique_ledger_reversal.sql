-- 한 원장 거래는 한 번만 정정(반대분개)된다.
-- 서비스도 이미 정정된 거래를 거부하지만, 동시에 들어온 두 정정 요청은 서비스 검사를 함께 통과할 수 있다.
alter table ledger_transactions
    add constraint uk_ledger_transactions_reversal_of_id unique (reversal_of_id);
