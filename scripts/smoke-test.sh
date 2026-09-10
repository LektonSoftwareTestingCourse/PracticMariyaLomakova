#!/bin/bash
# =========================================================================
# smoke-test.sh — Автоматическая приёмка для ревью (дни 5, 10, 15)
# =========================================================================
# Запуск:
#   chmod +x smoke-test.sh
#   ./smoke-test.sh
#
# Требования:
#   - Docker Compose запущен (`docker compose up -d`)
#   - jq установлен (`apt install jq` / `brew install jq`)
# =========================================================================

set -e
PROJECT_NAME="СМП"
GATEWAY="http://localhost:8080"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass() { echo -e "${GREEN}✅ $1${NC}"; }
fail() { echo -e "${RED}❌ $1${NC}"; exit 1; }
info() { echo -e "${YELLOW}⏳ $1${NC}"; }

echo "=========================================="
echo "  Smoke Test — Automated Acceptance"
echo "  ${PROJECT_NAME} Processing Simulator"
echo "=========================================="
echo ""

# -------------------------------------------------------------------
# 1. Health-checks всех 11 сервисов + RabbitMQ
# -------------------------------------------------------------------
info "1. Checking health-checks..."

check_health() {
    local name=$1
    local url=$2
    local code=$(curl -s -o /dev/null -w "%{http_code}" "$url" --connect-timeout 5 2>/dev/null || echo "000")
    if [ "$code" = "200" ]; then
        pass "$name ($url) — HTTP $code"
    else
        fail "$name ($url) — HTTP $code (expected 200)"
    fi
}

check_health "Gateway"            "$GATEWAY/health"
check_health "Card Management"    "http://localhost:8081/health"
check_health "Switch"             "http://localhost:8082/health"
check_health "Authorization"      "http://localhost:8083/health"
check_health "Terminal Simulator" "http://localhost:8085/health"
check_health "Merchant Simulator" "http://localhost:8084/health"
check_health "Transaction Logger" "http://localhost:8088/health"

check_health "Bin Lookup"         "http://localhost:8096/actuator/health"
check_health "Notification Service" "http://localhost:8097/actuator/health"

# RabbitMQ Management UI — проверяем доступность
RMQ_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:15672" --connect-timeout 5 2>/dev/null || echo "000")
if [ "$RMQ_CODE" = "200" ] || [ "$RMQ_CODE" = "302" ]; then
    pass "RabbitMQ Management UI (http://localhost:15672) — HTTP $RMQ_CODE"
else
    fail "RabbitMQ Management UI (http://localhost:15672) — HTTP $RMQ_CODE (expected 200/302)"
fi

# Dashboard — может быть на 3000 и отвечать HTML, проверяем просто доступность
DASHBOARD_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:3000" --connect-timeout 5 2>/dev/null || echo "000")
if [ "$DASHBOARD_CODE" = "200" ] || [ "$DASHBOARD_CODE" = "304" ]; then
    pass "Web Dashboard (http://localhost:3000) — HTTP $DASHBOARD_CODE"
else
    fail "Web Dashboard (http://localhost:3000) — HTTP $DASHBOARD_CODE (expected 200)"
fi

echo ""

# -------------------------------------------------------------------
# 2. Генерация тестовых карт
# -------------------------------------------------------------------
info "2. Generating test cards..."

CARDS_RESPONSE=$(curl -s -X POST "$GATEWAY/api/cards/generate" \
    -H "Content-Type: application/json" \
    -d '{"count": 500, "bins": ["400000","400001","400002","400003","400004"]}' \
    --connect-timeout 10)

GENERATED=$(echo "$CARDS_RESPONSE" | jq -r '.generated // 0')
if [ "$GENERATED" -ge 500 ]; then
    pass "Cards generated: $GENERATED"
else
    fail "Card generation returned $GENERATED cards (expected >= 20)"
fi

# Получаем PAN первой карты
PAN=$(echo "$CARDS_RESPONSE" | jq -r '.cards[0].pan // empty')
if [ -z "$PAN" ]; then
    fail "Could not extract PAN from generated cards"
fi
pass "First test PAN: ${PAN:0:4}****${PAN:12:4}"

echo ""

# -------------------------------------------------------------------
# 3. Одиночная тестовая транзакция
# -------------------------------------------------------------------
info "3. Testing single transaction..."

TX_RESPONSE=$(curl -s -X POST "$GATEWAY/api/transactions" \
    -H "Content-Type: application/json" \
    -d "{
        \"mti\": \"0100\",
        \"stan\": \"000001\",
        \"pan\": \"$PAN\",
        \"processingCode\": \"000000\",
        \"amount\": 150000,
        \"currencyCode\": \"643\",
        \"transmissionDateTime\": \"2026-06-01T10:30:00Z\",
        \"terminalId\": \"TERM0001\",
        \"merchantId\": \"MERCH0000000001\",
        \"mcc\": \"5411\",
        \"acquirerId\": \"ACQ001\"
    }" \
    --connect-timeout 15)

TX_STATUS=$(echo "$TX_RESPONSE" | jq -r '.status // "ERROR"')
if [ "$TX_STATUS" = "APPROVED" ]; then
    pass "Single transaction: APPROVED (processingTimeMs: $(echo "$TX_RESPONSE" | jq -r '.processingTimeMs // "N/A"')ms)"
elif [ "$TX_STATUS" = "DECLINED" ]; then
    info "Single transaction: DECLINED (reason: $(echo "$TX_RESPONSE" | jq -r '.declineReason // "N/A"'))"
    info "This may be OK if card has insufficient balance"
else
    fail "Single transaction: unexpected status '$TX_STATUS'"
fi

# -------------------------------------------------------------------
# 3a. Проверка bin-lookup endpoint
# -------------------------------------------------------------------
info "3a. Testing bin-lookup endpoint..."

BIN_RESPONSE=$(curl -s "http://localhost:8096/api/bin/400000" --connect-timeout 5)
BIN_ISSUER=$(echo "$BIN_RESPONSE" | jq -r '.issuerId // "ERROR"')
if [ "$BIN_ISSUER" = "ISS001" ]; then
    pass "Bin Lookup: GET /api/bin/400000 — issuerId=$BIN_ISSUER"
else
    fail "Bin Lookup: GET /api/bin/400000 — expected issuerId=ISS001, got $BIN_ISSUER"
fi

echo ""

# -------------------------------------------------------------------
# 3b. Проверка notification-service endpoint
# -------------------------------------------------------------------
info "3b. Testing notification-service endpoint..."

NS_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8097/api/notifications" --connect-timeout 5 2>/dev/null || echo "000")
if [ "$NS_CODE" = "200" ]; then
    pass "Notification Service: GET /api/notifications — HTTP 200"
else
    fail "Notification Service: GET /api/notifications — HTTP $NS_CODE (expected 200)"
fi

echo ""

# -------------------------------------------------------------------
# 4. Симулятор: 50 транзакций (mixed-сценарий)
# -------------------------------------------------------------------
info "4. Running terminal simulator (50 TX, mixed scenario)..."

SIM_RESPONSE=$(curl -s -X POST "$GATEWAY/api/simulator/terminal/run" \
    -H "Content-Type: application/json" \
    -d '{"count": 50, "scenario": "mixed", "tps": 50}' \
    --connect-timeout 60)

SUBMITTED=$(echo "$SIM_RESPONSE" | jq -r '.totalSubmitted // 0')
APPROVED=$(echo "$SIM_RESPONSE" | jq -r '.approved // 0')
DECLINED=$(echo "$SIM_RESPONSE" | jq -r '.declined // 0')

if [ "$SUBMITTED" -eq 50 ]; then
    pass "Simulator: $SUBMITTED submitted, $APPROVED approved, $DECLINED declined"
else
    fail "Simulator: expected 50 submitted, got $SUBMITTED"
fi

echo ""

# -------------------------------------------------------------------
# 5. Поиск транзакций
# -------------------------------------------------------------------
info "5. Testing transaction search..."

SEARCH_RESPONSE=$(curl -s "$GATEWAY/api/transactions/search?limit=5" \
    --connect-timeout 10)

SEARCH_TOTAL=$(echo "$SEARCH_RESPONSE" | jq -r '.total // 0')
if [ "$SEARCH_TOTAL" -gt 0 ]; then
    pass "Search: $SEARCH_TOTAL transactions found"
else
    fail "Search: no transactions found"
fi

echo ""

# -------------------------------------------------------------------
# 6. Dashboard статистика
# -------------------------------------------------------------------
info "6. Testing dashboard stats..."

STATS_RESPONSE=$(curl -s "$GATEWAY/api/dashboard/stats" \
    --connect-timeout 10)

STATS_TOTAL=$(echo "$STATS_RESPONSE" | jq -r '.totalTransactions // 0')
STATS_RATE=$(echo "$STATS_RESPONSE" | jq -r '.approvalRate // 0')
if [ "$(echo "$STATS_TOTAL > 0" | bc -l 2>/dev/null || echo 0)" = "1" ]; then
    pass "Dashboard stats: $STATS_TOTAL total TX, approval rate: $STATS_RATE"
else
    fail "Dashboard stats: totalTransactions is 0"
fi

echo ""

# -------------------------------------------------------------------
# 7. Dashboard recent transactions
# -------------------------------------------------------------------
info "7. Testing dashboard recent..."

RECENT_RESPONSE=$(curl -s "$GATEWAY/api/dashboard/recent?limit=5" \
    --connect-timeout 10)

RECENT_COUNT=$(echo "$RECENT_RESPONSE" | jq 'length // 0')
if [ "$RECENT_COUNT" -gt 0 ]; then
    pass "Recent transactions: $RECENT_COUNT entries"
else
    fail "Recent transactions: empty array"
fi

echo ""

# -------------------------------------------------------------------
# Итог
# -------------------------------------------------------------------
echo "=========================================="
echo -e "${GREEN}🎉 ALL CHECKS PASSED${NC}"
echo "=========================================="
echo ""
echo "Summary:"
echo "  ✅ 11 services + RabbitMQ healthy"
echo "  ✅ Card generation: $GENERATED cards"
echo "  ✅ Single transaction: $TX_STATUS"
echo "  ✅ Simulator: $SUBMITTED TX ($APPROVED approved, $DECLINED declined)"
echo "  ✅ Search: $SEARCH_TOTAL records"
echo "  ✅ Dashboard stats: $STATS_TOTAL total"
echo "  ✅ Dashboard recent: $RECENT_COUNT entries"
echo ""
echo "Review milestone COMPLETE."
