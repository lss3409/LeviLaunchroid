// v0.4.201：批量改 online_share_text（P/%1$s → %1$s 修双前缀；
// 尾加 ————————%2$s 时间戳）+ 新增 online_clipboard_expired 两条
// 用法：bun hrd_db/fix_share_v040201.mjs
import { readFileSync, writeFileSync, readdirSync } from 'fs';
import { join } from 'path';

const RES = 'app/src/main/res';
const EXPIRED = {
  zh: ['邀请码已失效', '房间已不存在或邀请超时了，请让房主重新分享一份新的邀请喵'],
  en: ['Invite expired', 'The room no longer exists or the invite timed out. Ask the host for a fresh one.'],
  es: ['Invitación caducada', 'La sala ya no existe o la invitación expiró. Pide al anfitrión una nueva.'],
  fr: ['Invitation expirée', 'La salle n’existe plus ou l’invitation a expiré. Demandez une nouvelle invitation à l’hôte.'],
  hi: ['निमंत्रण समाप्त', 'कमरा मौजूद नहीं है या निमंत्रण की समय सीमा समाप्त हो गई। होस्ट से नया निमंत्रण मांगें।'],
  idn: ['Undangan kedaluwarsa', 'Ruangan tidak ada lagi atau undangan telah kedaluwarsa. Minta undangan baru dari host.'],
  ja: ['招待の有効期限切れ', 'ルームが存在しないか、招待の有効期限が切れています。ホストに新しい招待をもらってください。'],
  pt: ['Convite expirado', 'A sala não existe mais ou o convite expirou. Peça um novo convite ao anfitrião.'],
  ru: ['Приглашение недействительно', 'Комната больше не существует или срок приглашения истёк. Попросите у хоста новое.'],
  tr: ['Davet geçersiz', 'Oda artık yok veya davetin süresi doldu. Sunucudan yeni bir davet isteyin.'],
  vi: ['Lời mời hết hạn', 'Phòng không còn tồn tại hoặc lời mời đã hết hạn. Hãy xin chủ phòng lời mời mới.'],
};

// 语言目录 → 过期文案 key（values-zh-rCN 用 zh 等）
const langOf = (dir) => {
  if (dir === 'values') return 'en';
  const m = dir.match(/^values-([a-z]+)/);
  if (!m) return null;
  const code = m[1];
  if (code === 'zh') return 'zh';
  if (code === 'idn') return 'idn';
  return code;
};

let changed = 0;
for (const d of readdirSync(RES)) {
  if (!d.startsWith('values')) continue;
  const lang = langOf(d);
  if (!lang || !EXPIRED[lang]) continue;
  const p = join(RES, d, 'strings.xml');
  let s = readFileSync(p, 'utf8');
  if (!s.includes('online_share_text')) continue;

  // 1) P/%1$s → %1$s（修「P/P/xxxx」双前缀）
  const before = s;
  s = s.replaceAll('P/%1$s', '%1$s');

  // 2) online_share_text 块尾 </string> 前插 ————————%2$s
  const blockRe = /(<string name="online_share_text">[\s\S]*?)(<\/string>)/;
  const bm = s.match(blockRe);
  if (!bm) {
    console.log('SKIP (no block):', p);
    continue;
  }
  if (!bm[0].includes('%2$s')) {
    s = s.replace(blockRe, `$1————————%2$s$2`);
  }

  // 3) 新增过期提示两条（插到 online_clipboard_join 后）
  const [t, msg] = EXPIRED[lang];
  const anchor = '<string name="online_clipboard_join">';
  if (s.includes(anchor) && !s.includes('online_clipboard_expired_title')) {
    s = s.replace(anchor,
      `<string name="online_clipboard_expired_title">${t}</string>\n    <string name="online_clipboard_expired_msg">${msg}</string>\n    ` + anchor);
  }
  if (s !== before) {
    writeFileSync(p, s);
    changed++;
    console.log('OK:', p);
  }
}
console.log('changed files:', changed);
