-- config/application.yml 의 nara.filter 값을 1회 이관한다
INSERT INTO filter_term (type, value, created_at)
SELECT 'KEYWORD', v, now() FROM unnest(ARRAY[
  '타당성','타당성조사','사전타당성','예비타당성','타당성 검토','투자타당성','타당성평가 기준','기본계획','마스터플랜','ODA',
  'EDCF','대외경제협력기금','개발협력','사후평가','추적조사','PMC','KSP','재무','재무분석','재무모델',
  '재무성 분석','회계','원가','경제성분석','가치평가','투자검토','투자사업 평가','사업비 검증','펀드','기업지원펀드',
  '사업추진전략','재원조달','PPP','해외진출','사업화 전략','컨설팅','고속철도','철도신설','철도개발전략','철도시설물',
  '관제','재해','교량','LNG','천연가스','가스','발전소','신재생','기후변화','탄소중립',
  '그린','국립병원','암센터','보건의료','의료','수자원','수자원관리','정수장','하수처리','상하수도',
  '국제업무지구','지역개발'
]) WITH ORDINALITY AS t(v, ord) ORDER BY ord
ON CONFLICT DO NOTHING;

INSERT INTO filter_term (type, value, created_at)
SELECT 'AGENCY', v, now() FROM unnest(ARRAY[
  '한국국제협력단','한국수출입은행','해외건설협회','한국교통연구원','한국가스공사',
  '한국서부발전','한국자산관리공사','한국해외인프라도시개발지원공사','한국개발연구원','한국환경산업기술원'
]) WITH ORDINALITY AS t(v, ord) ORDER BY ord
ON CONFLICT DO NOTHING;

INSERT INTO filter_setting (id, categories, price_min, price_max, updated_at)
VALUES (1, 'GOODS,SERVICE,CONSTRUCTION,FOREIGN', NULL, NULL, now());
