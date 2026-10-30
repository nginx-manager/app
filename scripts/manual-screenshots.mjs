// 사용 설명서(src/main/resources/guide)에 넣을 화면을 실제 이미지로 찍는다.
//
//   NGINX_MANAGER_VERSION=<버전> docker compose -f docker/docker-compose.yml -p nm-manual up -d --wait
//   TRAFFIC_URL=http://localhost IMPORT_PATTERN='/home/*/nginx/*.conf' node scripts/manual-screenshots.mjs http://localhost:9000 src/main/resources/static/guide/images
//
// 빈 볼륨으로 띄운 컨테이너에 관리자 계정을 만들고, 예시 사이트·스트림·인증서·사용자를 만들며 설명서의 장 순서대로 찍는다.
// 그림은 2배(deviceScaleFactor 2)로 찍고, 설명서에서는 width로 1배 크기에 맞춰 보여 준다.
// playwright 패키지가 필요하다(npm i -g playwright 또는 NODE_PATH로 지정). 인증서 업로드에 openssl을 쓴다.
// 설정 파일 가져오기 화면은 IMPORT_PATTERN을 줄 때만 찍는다. 예시 파일 준비는 docs/guide.md.
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync } from 'node:fs';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');

const [base = 'http://localhost:9000', outDir = 'src/main/resources/static/guide/images'] = process.argv.slice(2);
const USER = 'admin';
const PASSWORD = 'manual-shots-2026!';
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

async function submit(locator) {
    await Promise.all([page.waitForNavigation(), page.locator(locator).first().click()]);
    await page.waitForLoadState('networkidle');
}

async function applyPending(message) {
    await go('/apply');
    await page.fill('input[name=message]', message);
    await page.click('button:has-text("nginx -t 후 reload")');
    await page.waitForSelector('#result:has-text("기록했습니다")', { timeout: 60000 });
    await page.evaluate(() => window.scrollTo(0, 0));
}

// 2장: 최초 관리자 계정, 로그인
await go('/');
if (page.url().includes('/setup')) {
    await page.fill('input[name=username]', USER);
    await page.fill('input[name=password]', PASSWORD);
    await page.fill('input[name=passwordConfirm]', PASSWORD);
    await shot('setup');
    await submit('button[type=submit]');
}
await go('/login');
await page.fill('input[name=username]', USER);
await shot('login');
await page.fill('input[name=password]', PASSWORD);
await submit('button[type=submit]');

// 9장 준비: 업로드 인증서(와일드카드, 자체 서명)
const certDir = mkdtempSync(join(tmpdir(), 'nm-cert-'));
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '90',
    '-subj', '/CN=*.example.com', '-addext', 'subjectAltName=DNS:*.example.com,DNS:example.com',
    '-keyout', join(certDir, 'key.pem'), '-out', join(certDir, 'cert.pem')], { stdio: 'ignore' });
await go('/certs');
await page.fill('input[name=name]', 'wildcard.example.com');
await page.setInputFiles('input[name=certificate]', join(certDir, 'cert.pem'));
await page.setInputFiles('input[name=privateKey]', join(certDir, 'key.pem'));
await submit('button:has-text("업로드")');

// 4장: 사이트 만들기
await go('/sites/new');
await page.fill('input[name=primaryDomain]', 'app.example.com');
await page.check('input[name=type][value=PROXY]');
await page.fill('textarea[name=proxyTarget]', 'http://10.0.0.11:8080\nhttp://10.0.0.12:8080');
await page.locator('details.proxy-options summary').click();
await page.check('input[name=proxyWebsocket]');
await page.waitForTimeout(800);
await shot('site-form');
await page.check('input[name=certificateMode][value=UPLOADED]');
await page.selectOption('select[name=certificateId]', { label: 'wildcard.example.com (업로드)' });
await page.waitForTimeout(500);
await shot('site-form-https', { selector: 'fieldset.https-choice' });
await submit('#site-form button[type=submit]');

await go('/sites/new');
await page.fill('input[name=primaryDomain]', 'docs.example.com');
await page.check('input[name=type][value=STATIC]');
await page.fill('input[name=staticRoot]', '/usr/share/nginx/html');
await submit('#site-form button[type=submit]');

await go('/sites/new');
await page.fill('input[name=primaryDomain]', 'old.example.com');
await page.check('input[name=type][value=REDIRECT]');
await page.fill('input[name=redirectTarget]', 'https://docs.example.com');
await submit('#site-form button[type=submit]');

// 6장: 스트림
await go('/streams/new');
await page.check('input[name=protocol][value=TCP]');
await page.fill('input[name=listenPort]', '5432');
await page.fill('textarea[name=backends]', '10.0.0.21:5432\n10.0.0.22:5432');
await page.fill('input[name=note]', '사내 PostgreSQL');
await page.waitForTimeout(800);
await shot('stream-form');
await submit('#stream-form button[type=submit]');

// 7장: 적용
await go('/apply');
await shot('apply');
await applyPending('사이트 세 개와 DB 스트림 추가');
await shot('apply-result', { selector: '#result' });

// 4장: 목록과 사이트 화면, 한 번 더 바꿔 이력을 남긴다
await go('/sites');
await shot('sites');
await page.locator('a', { hasText: 'app.example.com' }).first().click();
await page.waitForLoadState('networkidle');
await shot('site-detail');
await page.locator('details.proxy-options summary').click().catch(() => {});
await page.fill('input[name=proxyReadTimeout]', '120');
// 사이트 화면 위쪽의 담당자 폼에도 "담당자 저장" 단추가 있어, 설정 폼의 저장을 고른다
await submit('form:not([action$="/owner"]) button[type=submit]:has-text("저장")');
await applyPending('app 응답 타임아웃 120초');

// 8장: 이력, 비교, 버전 화면
await go('/versions');
await shot('versions');
await submit('button:has-text("diff 보기")');
await shot('version-diff');
await go('/versions');
await page.locator('table a').nth(1).click();
await page.waitForLoadState('networkidle');
await shot('version-detail');

// 10장: 설정 파일
await go('/files');
await shot('files');
await go('/files/new');
await page.fill('input[name=path]', 'njs/hello.js');
await page.locator('textarea[name=content]').evaluate((el, v) => { el.value = v; el.dispatchEvent(new Event('input')); },
    'function hello(r) {\n    r.return(200, "hello\\n");\n}\n\nexport default { hello };\n');
await page.waitForTimeout(500);
await shot('file-new');

// 9장: 인증서, 발급 설정
await go('/certs');
await shot('certs');
await go('/settings/acme');
await shot('acme');

// 11장: 로그
const traffic = process.env.TRAFFIC_URL;
if (traffic) {
    const paths = ['/', '/index.html', '/guide/install', '/favicon.ico', '/assets/app.css', '/50x.html'];
    for (let i = 0; i < 24; i++) {
        await new Promise((resolve) => {
            const req = http.get(traffic + paths[i % paths.length], { headers: { host: 'docs.example.com' }, timeout: 3000 }, (res) => {
                res.resume();
                res.on('end', resolve);
            });
            req.on('timeout', () => req.destroy());
            req.on('error', resolve);
        });
    }
}
await go('/sites');
await page.locator('a', { hasText: 'docs.example.com' }).first().click();
await page.waitForLoadState('networkidle');
await page.click('a:has-text("로그 보기")');
await page.waitForLoadState('domcontentloaded');
await page.waitForTimeout(1500);
await shot('logs');

// 12장: 사용자, 알림, 감사 로그
await go('/users');
for (const [name, role] of [['kim.dev', 'MEMBER'], ['lee.ops', 'MEMBER']]) {
    await page.fill('form[action$="/users"] input[name=username]', name);
    await page.fill('form[action$="/users"] input[name=password]', 'welcome-2026-nm');
    await page.selectOption('form[action$="/users"] select[name=role]', role);
    await submit('form[action$="/users"] button[type=submit]');
}
// 4장: 사이트 담당자. docs.example.com을 멤버 kim.dev에게 맡긴다
await go('/sites');
await page.locator('a', { hasText: 'docs.example.com' }).first().click();
await page.waitForLoadState('networkidle');
await page.selectOption('form[action$="/owner"] select[name=owner]', { label: 'kim.dev' });
await submit('form[action$="/owner"] button[type=submit]');
await shot('site-owner', { selector: 'form[action$="/owner"]' });
await go('/users');
await shot('users');
await go('/settings/notifications');
await page.selectOption('select[name=type]', 'WEBHOOK');
await page.fill('input[name=target]', 'https://hooks.example.com/services/T000/B000/XXXX');
await submit('button:has-text("추가")');
await page.selectOption('select[name=type]', 'EMAIL');
await page.fill('input[name=target]', 'ops@example.com');
await submit('button:has-text("추가")');
await shot('notifications');
await go('/account');
await shot('account');
await go('/audit');
await shot('audit');

// 5장: 설정 파일 가져오기
const importPattern = process.env.IMPORT_PATTERN;
if (importPattern) {
    await go('/sites/import');
    await page.fill('form[action$="/sites/import/sources"] input[name=pattern]', importPattern);
    await submit('button:has-text("위치 등록")');
    await submit('button:has-text("등록한 위치 모두 스캔")');
    await shot('import', { fullPage: true });
    const formRow = page.locator('tr', { has: page.locator('.badge:has-text("폼 사이트")') }).first();
    await go(await formRow.locator('a:has-text("미리 보기")').getAttribute('href'));
    await shot('import-preview', { fullPage: true });
}

// 3장과 2장: 대시보드, 모듈, footer
await go('/');
await shot('dashboard');
await page.locator('#modules summary').click();
await page.waitForTimeout(300);
await shot('modules', { selector: '#modules' });
await page.locator('footer.footer').scrollIntoViewIfNeeded();
const footerBox = await page.locator('footer.footer').boundingBox();
await page.screenshot({ path: join(outDir, 'footer.png'), clip: { x: footerBox.x - 12, y: footerBox.y, width: 420, height: footerBox.height } });

// 2장: 관리도구 안의 사용 설명서
await go('/guide/04-sites');
await shot('guide');

// 4장: 멤버가 보는 사이트 목록(담당 사이트만)
const member = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2, locale: 'ko-KR' });
await member.goto(base + '/login');
await member.fill('input[name=username]', 'kim.dev');
await member.fill('input[name=password]', 'welcome-2026-nm');
await Promise.all([member.waitForNavigation(), member.click('button[type=submit]')]);
await member.goto(base + '/sites');
await member.waitForLoadState('networkidle');
await member.screenshot({ path: join(outDir, 'member-sites.png') });
console.log('saved', join(outDir, 'member-sites.png'));
await member.close();

// 휴대폰 폭(390px): 메뉴 버튼으로 연 메뉴
await page.setViewportSize({ width: 390, height: 844 });
await go('/sites');
await page.click('.nav-toggle');
await page.waitForTimeout(300);
await shot('mobile-menu');

await browser.close();
