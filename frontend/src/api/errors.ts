/** 서버에 닿지 못한 경우(연결 실패, 잠든 서버를 깨우는 중 호스팅이 돌려주는 502/503/504 페이지)를 서비스 자체의 오류와 구분한다. */
export class ServerUnreachableError extends Error {
  constructor() {
    super(UNREACHABLE_MESSAGE)
    this.name = 'ServerUnreachableError'
  }
}

export const UNREACHABLE_MESSAGE =
  '서버에 연결할 수 없어요. 서버가 깨어나는 중일 수 있어요. 화면 위 안내가 "서버가 준비됐어요"로 바뀐 뒤 다시 시도해 주세요.'

export function isUnreachable(e: unknown): boolean {
  // fetch가 네트워크 오류로 실패하면 TypeError가 난다
  return e instanceof ServerUnreachableError || e instanceof TypeError
}

/** 화면에 보여 줄 오류 문구: 연결 문제면 안내 문구, 서버가 준 메시지가 있으면 그것, 없으면 기본 문구 */
export function errorText(e: unknown, fallback: string): string {
  if (isUnreachable(e)) return UNREACHABLE_MESSAGE
  return e instanceof Error && e.message ? e.message : fallback
}
