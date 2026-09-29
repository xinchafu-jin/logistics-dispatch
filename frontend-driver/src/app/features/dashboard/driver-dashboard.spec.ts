import {DriverMessageDto} from '../../core/services/driver-operations.models';
import {
  CASE_CATEGORIES,
  CASE_DESCRIPTION_MAX_LENGTH,
  caseCategoryOption,
  caseDisplayStatus,
  composeCaseDescription,
  findNearest,
  formatManeuverDistance,
  isStepPassed,
  maneuverIcon,
  measureStepProgress,
  mergeMessagesById,
  navigationReadyForOrder,
  pendingLoadingOrderCount,
  validateCaseDraft,
} from './driver-dashboard';

describe('倉庫點交後才能導航', () => {
  it('同一司機還有任何待點交訂單時，其他已點交訂單也不能導航', () => {
    const tasks = {routes: [{stops: [
      {orderId: 1, orderStatus: 'LOADED' as const},
      {orderId: 2, orderStatus: 'CONFIRMED' as const},
    ]}]};
    expect(pendingLoadingOrderCount(tasks)).toBe(1);
    expect(navigationReadyForOrder(tasks, 1)).toBe(false);
  });

  it('所有待配送訂單完成點交後，已點交的訂單可以導航', () => {
    const tasks = {routes: [{stops: [
      {orderId: 1, orderStatus: 'LOADED' as const},
      {orderId: 2, orderStatus: 'IN_DELIVERY' as const},
    ]}]};
    expect(pendingLoadingOrderCount(tasks)).toBe(0);
    expect(navigationReadyForOrder(tasks, 1)).toBe(true);
    expect(navigationReadyForOrder(tasks, 2)).toBe(true);
  });

  it('失敗或不存在的訂單不能導航；失敗訂單不阻擋其他已點交訂單', () => {
    const tasks = {routes: [{stops: [
      {orderId: 1, orderStatus: 'FAILED' as const},
      {orderId: 2, orderStatus: 'LOADED' as const},
    ]}]};
    expect(navigationReadyForOrder(tasks, 1)).toBe(false);
    expect(navigationReadyForOrder(tasks, 2)).toBe(true);
    expect(navigationReadyForOrder(tasks, 3)).toBe(false);
    expect(navigationReadyForOrder(null, 2)).toBe(false);
  });
});

describe('findNearest', () => {
  // 台南往北的三個點，間隔約 3 公里
  const route = [
    [120.2255, 22.9908] as [number, number],
    [120.2578, 23.0264] as [number, number],
    [120.2954, 23.0781] as [number, number],
  ];

  it('剛好站在某個路線點上時，距離接近 0、索引正確', () => {
    const result = findNearest([120.2578, 23.0264], route);

    expect(result.index).toBe(1);
    expect(result.distance).toBeLessThan(1);
  });

  it('偏離路線時回報實際距離', () => {
    // 往東偏 0.1 度，約 10 公里
    const result = findNearest([120.3578, 23.0264], route);

    expect(result.distance).toBeGreaterThan(5000);
  });

  it('在起點附近時索引是 0', () => {
    const result = findNearest([120.2256, 22.9910], route);

    expect(result.index).toBe(0);
  });

  it('空路線回傳 Infinity，不會爆', () => {
    const result = findNearest([120.22, 22.99], []);

    expect(result.distance).toBe(Infinity);
    expect(result.index).toBe(0);
  });
});

// 高雄一條往東的直路：每個點間隔 0.0001 度經度，約 10.3 公尺
const eastRoad: [number, number][] = Array.from({length: 30}, (_, i) => [120.3 + i * 0.0001, 22.6273]);

describe('measureStepProgress', () => {
  it('轉彎點在前方路線上：回傳沿路線的距離', () => {
    const result = measureStepProgress(eastRoad[0], eastRoad, eastRoad[10]);

    expect(result.onRemainingRoute).toBe(true);
    // 10 段、每段約 10.3 公尺
    expect(result.alongRouteMeters).toBeGreaterThan(95);
    expect(result.alongRouteMeters).toBeLessThan(110);
  });

  it('走過的部分已經切掉、轉彎點不在剩下的路線上：代表已經開過去', () => {
    // 司機已經開到第 15 個點，剩下的路線從第 15 點開始，轉彎點在第 5 點
    const result = measureStepProgress(eastRoad[15], eastRoad.slice(15), eastRoad[5]);

    expect(result.onRemainingRoute).toBe(false);
  });

  it('路線是空的：當作不在路線上，距離退回直線，不會爆', () => {
    const result = measureStepProgress(eastRoad[0], [], eastRoad[10]);

    expect(result.onRemainingRoute).toBe(false);
    expect(result.alongRouteMeters).toBeGreaterThan(95);
  });
});

describe('isStepPassed', () => {
  it('轉彎點已經不在剩下的路線上：算轉過', () => {
    expect(isStepPassed(500, 500, false)).toBe(true);
  });

  it('開到轉彎點旁邊、之後離開 20 公尺以上：算轉過', () => {
    expect(isStepPassed(25, 3, true)).toBe(true);
  });

  it('開到轉彎點旁邊但還沒離開：還沒轉', () => {
    expect(isStepPassed(10, 3, true)).toBe(false);
  });

  it('起點的迴轉點就在腳下、司機還沒動：不能一開始就跳過迴轉提示', () => {
    expect(isStepPassed(2, 2, true)).toBe(false);
  });

  it('從來沒靠近過轉彎點（最近 40 公尺），只是在附近飄：不算轉過', () => {
    expect(isStepPassed(70, 40, true)).toBe(false);
  });
});

describe('formatManeuverDistance', () => {
  it('20 公尺內直接說即將', () => {
    expect(formatManeuverDistance(12)).toBe('即將');
  });

  it('100 公尺內取到 5 公尺', () => {
    expect(formatManeuverDistance(47)).toBe('45 公尺');
  });

  it('1 公里內取到 10 公尺', () => {
    expect(formatManeuverDistance(263)).toBe('260 公尺');
  });

  it('超過 1 公里改用公里、一位小數', () => {
    expect(formatManeuverDistance(1234)).toBe('1.2 公里');
  });
});

describe('maneuverIcon', () => {
  it('一般轉彎依方向', () => {
    expect(maneuverIcon({type: 'turn', modifier: 'left'})).toBe('turn_left');
    expect(maneuverIcon({type: 'turn', modifier: 'sharp right'})).toBe('turn_sharp_right');
  });

  it('OSRM 起點常出現的 continue＋uturn 要顯示迴轉，不是直行', () => {
    expect(maneuverIcon({type: 'continue', modifier: 'uturn'})).toBe('u_turn_left');
  });

  it('圓環、岔路、匝道看類型，再看左右', () => {
    expect(maneuverIcon({type: 'roundabout', modifier: 'right'})).toBe('roundabout_right');
    expect(maneuverIcon({type: 'fork', modifier: 'slight left'})).toBe('fork_left');
    expect(maneuverIcon({type: 'off ramp', modifier: 'slight right'})).toBe('ramp_right');
  });

  it('抵達用旗子；直行或沒有方向用直行箭頭', () => {
    expect(maneuverIcon({type: 'arrive', modifier: 'left'})).toBe('flag');
    expect(maneuverIcon({type: 'new name', modifier: 'straight'})).toBe('straight');
    expect(maneuverIcon({type: 'notification', modifier: null})).toBe('straight');
  });
});

describe('caseDisplayStatus', () => {
  it('資料庫只有 OPEN／CLOSED：OPEN 還沒被接收是等待回覆，接收了是處理中', () => {
    expect(caseDisplayStatus({status: 'OPEN', acceptedAt: null})).toBe('waiting');
    expect(caseDisplayStatus({status: 'OPEN', acceptedAt: '2026-09-27T10:44:00'})).toBe('handling');
  });

  it('結案就是結案，不管有沒有人接收過', () => {
    expect(caseDisplayStatus({status: 'CLOSED', acceptedAt: null})).toBe('closed');
    expect(caseDisplayStatus({status: 'CLOSED', acceptedAt: '2026-09-27T10:44:00'})).toBe('closed');
  });
});

describe('caseCategoryOption', () => {
  it('每個分類代碼只出現一次，而且「其他」一定在', () => {
    const codes = CASE_CATEGORIES.map((option) => option.code);
    expect(new Set(codes).size).toBe(codes.length);
    expect(codes).toContain('OTHER');
  });

  it('後端多了前端不認識的分類時退回「其他」，畫面不會壞', () => {
    expect(caseCategoryOption('VEHICLE').label).toBe('車輛問題');
    expect(caseCategoryOption('UNKNOWN' as never).code).toBe('OTHER');
  });
});

describe('composeCaseDescription', () => {
  const vehicle = caseCategoryOption('VEHICLE');

  it('快選照分類裡的順序排，不照點的順序，再接補充說明', () => {
    expect(composeCaseDescription(vehicle, ['儀表警示燈亮', '爆胎'], ' 停在台 1 線路肩 ')).toBe(
      '爆胎、儀表警示燈亮：停在台 1 線路肩',
    );
  });

  it('只有快選或只有說明時不多加冒號', () => {
    expect(composeCaseDescription(vehicle, ['爆胎'], '  ')).toBe('爆胎');
    expect(composeCaseDescription(caseCategoryOption('OTHER'), [], '冷氣壞了')).toBe('冷氣壞了');
  });

  it('不屬於這個分類的快選不會被組進去（切換分類後殘留的選項）', () => {
    expect(composeCaseDescription(vehicle, ['嚴重塞車'], '')).toBe('');
  });
});

describe('validateCaseDraft', () => {
  it('說明空白、太長、沒選能不能繼續都要擋', () => {
    expect(validateCaseDraft('', true)).not.toBeNull();
    expect(validateCaseDraft('爆'.repeat(CASE_DESCRIPTION_MAX_LENGTH + 1), true)).not.toBeNull();
    expect(validateCaseDraft('爆胎', null)).not.toBeNull();
  });

  it('剛好 1000 字、選了不能繼續都可以送', () => {
    expect(validateCaseDraft('爆'.repeat(CASE_DESCRIPTION_MAX_LENGTH), true)).toBeNull();
    expect(validateCaseDraft('爆胎', false)).toBeNull();
  });
});

describe('mergeMessagesById', () => {
  const message = (id: number, content: string): DriverMessageDto => ({
    id,
    driverId: 1,
    senderType: 'ADMIN',
    content,
    createdAt: '2026-09-27T10:00:00',
    exceptionCaseId: 7,
  });

  it('推播和 API 回應是同一則時只留一則，用新來的那份（例如補上 readAt）', () => {
    const merged = mergeMessagesById([message(1, '收到')], [{...message(1, '收到'), readAt: '2026-09-27T10:01:00'}]);
    expect(merged).toHaveLength(1);
    expect(merged[0].readAt).toBe('2026-09-27T10:01:00');
  });

  it('不管到達順序，一律依 id 由舊到新', () => {
    const merged = mergeMessagesById([message(3, '三')], [message(1, '一'), message(2, '二')]);
    expect(merged.map((item) => item.id)).toEqual([1, 2, 3]);
  });
});
