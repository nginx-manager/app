// 릴리스 노트에 넣을 화면을 실제 이미지로 찍는다.
//
//   NGINX_MANAGER_VERSION=<버전> docker compose -f docker/docker-compose.yml -p nm-shots up -d --wait
//   TRAFFIC_URL=http://localhost node scripts/release-screenshots.mjs http://localhost:9000 docs/releases/images/v<버전>
//
// 자세한 절차는 docs/releases/README.md. 빈 볼륨으로 띄운 컨테이너에 관리자 계정을 만들고, 예시 사이트를 만들어 적용한 뒤 화면마다 찍는다.
// 그림은 레티나 2배(deviceScaleFactor 2)로 찍는다. 노트에서는 width로 실제 화면 크기(1배)에 맞춰 보여 준다.
// playwright 패키지가 필요하다(npm i -g playwright 또는 NODE_PATH로 지정).
// IMPORT_PATTERN(예: /home/*/nginx/*.conf)을 주면 그 위치를 등록해 설정 파일 가져오기 화면도 찍는다. 파일 준비는 README의 릴리스 절차.
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync } from 'node:fs';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// require로 읽어야 전역 설치·NODE_PATH의 playwright도 찾는다
const { chromium } = createRequire(import.meta.url)('playwright');

const [base = 'http://localhost:9000', outDir = 'shots'] = process.argv.slice(2);
const USER = 'admin';
const PASSWORD = 'release-notes-2026!';
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2, locale: 'ko-KR' });

async function shot(name, { fullPage = false, selector } = {}) {
    const path = join(outDir, `${name}.png`);
    if (selector) {
        await page.locator(selector).first().screenshot({ path });
    } else {
        await page.screenshot({ path, fullPage });
    }
    console.log('saved', path);
}

async function go(path) {
    await page.goto(base + path);
    await page.waitForLoadState('networkidle');
}

// 인증서 목록이 비지 않도록 자체 서명 인증서를 하나 올린다(openssl이 없으면 건너뛴다)
async function uploadSampleCertificate() {
    const dir = mkdtempSync(join(tmpdir(), 'nm-cert-'));
    try {
        execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '90',
            '-subj', '/CN=*.example.com', '-addext', 'subjectAltName=DNS:*.example.com,DNS:example.com',
            '-keyout', join(dir, 'key.pem'), '-out', join(dir, 'cert.pem')], { stdio: 'ignore' });
    } catch {
        console.log('openssl not found; skipping certificate upload');
        return;
    }
    await go('/certs');
    await page.fill('input[name=name]', 'wildcard.example.com');
    await page.setInputFiles('input[name=certificate]', join(dir, 'cert.pem'));
    await page.setInputFiles('input[name=privateKey]', join(dir, 'key.pem'));
    await Promise.all([page.waitForNavigation(), page.click('button:has-text("올리기")')]);
}

// 로그 화면이 비지 않도록 nginx(80 포트)에 요청을 보낸다. TRAFFIC_URL(예: http://localhost)을 줄 때만
async function sendSampleTraffic() {
    const target = process.env.TRAFFIC_URL;
    if (!target) {
        return;
    }
    // 백엔드가 없어도 바로 응답하는 정적 사이트로 보낸다
    const paths = ['/', '/index.html', '/guide/install', '/favicon.ico', '/assets/app.css', '/50x.html'];
    for (let i = 0; i < 24; i++) {
        await new Promise((resolve) => {
            const req = http.get(target + paths[i % paths.length], { headers: { host: 'docs.example.com' }, timeout: 3000 }, (res) => {
                res.resume();
                res.on('end', resolve);
            });
            req.on('timeout', () => req.destroy());
            req.on('error', resolve);
        });
    }
}

// 1. 최초 관리자 계정
await go('/');
if (page.url().includes('/setup')) {
    await page.fill('input[name=username]', USER);
    await page.fill('input[name=password]', PASSWORD);
    await page.fill('input[name=passwordConfirm]', PASSWORD);
    await page.click('button[type=submit]');
}
await go('/login');
await page.fill('input[name=username]', USER);
await page.fill('input[name=password]', PASSWORD);
await Promise.all([page.waitForNavigation(), page.click('button[type=submit]')]);

// 적용 폼은 htmx로 결과만 바꿔 끼운다. 검증·reload·상태 확인이 끝날 때까지 기다린다
async function applyPending(message) {
    await page.fill('input[name=message]', message);
    await page.click('button:has-text("검증 후 적용")');
    await page.waitForSelector('#result:has-text("기록했습니다")', { timeout: 60000 });
    await page.evaluate(() => window.scrollTo(0, 0));
}

// 2. 사이트 만들기: 폼과 미리보기
async function createSite(domain, fill) {
    await go('/sites/new');
    await page.fill('input[name=primaryDomain]', domain);
    await fill();
    await page.waitForTimeout(800); // 미리보기(htmx) 갱신
}
await createSite('app.example.com', async () => {
    await page.check('input[name=type][value=PROXY]');
    await page.fill('textarea[name=proxyTarget]', 'http://10.0.0.11:8080\nhttp://10.0.0.12:8080');
    await page.locator('details.proxy-options summary').click();
    await page.check('input[name=proxyWebsocket]');
});
await shot('site-form', { fullPage: true });
await Promise.all([page.waitForNavigation(), page.click('#site-form button[type=submit]')]);

await createSite('docs.example.com', async () => {
    await page.check('input[name=type][value=STATIC]');
    await page.fill('input[name=staticRoot]', '/usr/share/nginx/html');
});
await Promise.all([page.waitForNavigation(), page.click('#site-form button[type=submit]')]);

// TCP 스트림 프록시
await go('/streams/new');
await page.check('input[name=protocol][value=TCP]');
await page.fill('input[name=listenPort]', '5432');
await page.fill('textarea[name=backends]', '10.0.0.21:5432\n10.0.0.22:5432');
await page.fill('input[name=note]', '사내 PostgreSQL');
await page.waitForTimeout(800);
await shot('stream-form');
await Promise.all([page.waitForNavigation(), page.click('#stream-form button[type=submit]')]);

// 3. 적용 전 diff, 적용 결과
await go('/apply');
await shot('apply-diff');
await applyPending('사이트 두 개 추가');
await shot('apply-result', { selector: '#result' });

// 4. 한 번 더 바꿔 적용해 이력과 비교를 남긴다
await go('/sites');
await shot('sites');
await page.locator('a', { hasText: 'app.example.com' }).first().click();
await page.waitForLoadState('networkidle');
await page.locator('details.proxy-options summary').click().catch(() => {});
await page.fill('input[name=proxyReadTimeout]', '120');
await Promise.all([page.waitForNavigation(), page.locator('form:not([action$="/owner"]) button[type=submit]', { hasText: '저장' }).first().click()]);
await go('/apply');
await applyPending('읽기 타임아웃 120초');

await go('/versions');
await shot('versions');
// 기본으로 고른 최근 두 버전을 비교한다
await Promise.all([page.waitForNavigation(), page.click('button:has-text("비교")')]);
await shot('version-diff');

// 5. 대시보드, 인증서, 로그, 감사 로그
await go('/streams');
await shot('streams');
await go('/');
await shot('dashboard', { fullPage: true });
// 다른 언어와 다크 모드
await go('/?lang=en');
await shot('dashboard-en');
await page.evaluate(() => localStorage.setItem('nm-theme', 'dark'));
await go('/?lang=ko');
await shot('dashboard-dark');
await page.evaluate(() => localStorage.removeItem('nm-theme'));
await go('/');
// footer는 왼쪽(이름과 버전)만 잘라 찍는다
await page.locator('footer.footer').scrollIntoViewIfNeeded();
const footerBox = await page.locator('footer.footer').boundingBox();
await page.screenshot({ path: join(outDir, 'footer.png'), clip: { x: footerBox.x - 12, y: footerBox.y, width: 300, height: footerBox.height } });
await uploadSampleCertificate();
await go('/certs');
await shot('certs');
await sendSampleTraffic();
await go('/sites');
await page.locator('a', { hasText: 'docs.example.com' }).first().click();
await page.waitForLoadState('networkidle');
await page.click('a:has-text("로그 보기")');
await page.waitForLoadState('domcontentloaded');
await page.waitForTimeout(1500);
await shot('logs');
await go('/audit');
await shot('audit');

// 6. 설정 파일 가져오기: 위치 등록 → 검사 → 미리 보기(폼/원문) → 한꺼번에 등록 → 적용 화면
const importPattern = process.env.IMPORT_PATTERN;
if (importPattern) {
    await go('/sites/import');
    await page.fill('form[action$="/sites/import/sources"] input[name=pattern]', importPattern);
    await Promise.all([page.waitForNavigation(), page.click('button:has-text("위치 등록")')]);
    await Promise.all([page.waitForNavigation(), page.click('button:has-text("등록한 위치 모두 검사")')]);
    await page.waitForLoadState('networkidle');
    await shot('import-scan', { fullPage: true });
    const previews = page.locator('a:has-text("미리 보기")');
    const formRow = page.locator('tr', { has: page.locator('.badge:has-text("폼 사이트")') }).first();
    const rawRow = page.locator('tr', { has: page.locator('.badge:has-text("원문 사이트")') }).first();
    if (await previews.count()) {
        await go(await formRow.locator('a:has-text("미리 보기")').getAttribute('href'));
        await shot('import-preview-form', { fullPage: true });
        await go('/sites/import?scan=');
        await go(await rawRow.locator('a:has-text("미리 보기")').getAttribute('href'));
        await shot('import-preview-raw', { fullPage: true });
    }
    await go('/sites/import?scan=');
    await Promise.all([page.waitForNavigation(), page.click('button:has-text("고른 파일 등록")')]);
    await go('/apply');
    await shot('import-apply');
}

// 7. 휴대폰 폭(390px): 메뉴 버튼으로 연 메뉴
await page.setViewportSize({ width: 390, height: 844 });
await go('/sites');
await shot('mobile-sites');
await page.click('.nav-toggle');
await page.waitForTimeout(300);
await shot('mobile-menu');

await browser.close();
