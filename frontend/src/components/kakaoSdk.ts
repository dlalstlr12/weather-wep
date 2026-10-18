// Kakao 지도 SDK 로더. 지도와 장소 검색이 같은 스크립트를 쓰므로 한 번만 불러온다.
declare global {
  interface Window { kakao?: any }
}

export function loadKakaoSdk(appKey: string): Promise<any> {
  if (typeof window === 'undefined') return Promise.reject('no-window')
  if ((window as any).kakao && window.kakao.maps) return Promise.resolve(window.kakao)
  return new Promise((resolve, reject) => {
    const exist = document.querySelector('script[data-kakao-sdk="true"]') as HTMLScriptElement | null
    if (exist) {
      exist.addEventListener('load', () => resolve(window.kakao))
      exist.addEventListener('error', reject)
      return
    }
    const s = document.createElement('script')
    s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${appKey}&libraries=services&autoload=false`
    s.async = true
    s.defer = true
    s.dataset.kakaoSdk = 'true'
    s.addEventListener('load', () => resolve(window.kakao))
    s.addEventListener('error', reject)
    document.head.appendChild(s)
  })
}

/** SDK 스크립트를 불러오고 maps 모듈 초기화까지 끝난 kakao 객체를 돌려준다. */
export function ensureKakao(appKey: string): Promise<any> {
  return loadKakaoSdk(appKey).then((kakao) => new Promise((resolve) => kakao.maps.load(() => resolve(kakao))))
}
