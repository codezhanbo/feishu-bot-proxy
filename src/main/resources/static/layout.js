// 左侧菜单栏 + 手机端顶栏/抽屉：注入到每个页面，并高亮当前页。
// 桌面端侧栏常驻；≤768px 时侧栏收成抽屉，由顶部汉堡按钮 + 遮罩控制开合。
(function () {
  var page = document.body.getAttribute('data-page') || '';
  var nav = [
    { key: 'home',       href: '/home.html',       icon: '🏠', label: '首页' },
    { key: 'console',    href: '/console.html',    icon: '📋', label: '消息查询' },
    { key: 'stats',      href: '/stats.html',      icon: '📊', label: '数据统计' },
    { key: 'bots',       href: '/bots.html',       icon: '🤖', label: 'Bot 配置' },
    { key: 'ban-check',  href: '/ban-check.html',  icon: '🎮', label: '封禁查询' },
    { key: 'accounts',   href: '/accounts.html',   icon: '👤', label: '账号管理' },
    { key: 'alerts',     href: '/alerts.html',     icon: '🚨', label: '告警配置' },
    { key: 'alert-runs', href: '/alert-runs.html', icon: '🕒', label: '调度日志' },
    { key: 'alert-logs', href: '/alert-logs.html', icon: '📨', label: '告警日志' }
  ];

  var aside = document.getElementById('sidebar');

  // 品牌区
  var brand = document.createElement('div');
  brand.className = 'brand';
  brand.textContent = 'feishu-bot-proxy';
  aside.appendChild(brand);

  // 导航
  var navEl = document.createElement('nav');
  nav.forEach(function (item) {
    var a = document.createElement('a');
    a.href = item.href;
    a.innerHTML = '<span class="icon">' + item.icon + '</span>' + item.label;
    if (item.key === page) a.className = 'active';
    a.addEventListener('click', closeDrawer);
    navEl.appendChild(a);
  });
  aside.appendChild(navEl);

  // 登出
  var foot = document.createElement('div');
  foot.className = 'foot';
  var btn = document.createElement('button');
  btn.textContent = '登出';
  btn.addEventListener('click', function () {
    fetch('/console/logout', { method: 'POST' }).catch(function () {});
    location.replace('/login.html');
  });
  foot.appendChild(btn);
  aside.appendChild(foot);

  // 手机端顶栏（汉堡 + 品牌），桌面隐藏
  var topbar = document.createElement('div');
  topbar.className = 'topbar';
  var menuBtn = document.createElement('button');
  menuBtn.className = 'menu-btn';
  menuBtn.setAttribute('aria-label', '菜单');
  menuBtn.textContent = '☰';
  menuBtn.addEventListener('click', toggleDrawer);
  var topBrand = document.createElement('div');
  topBrand.className = 'brand';
  topBrand.textContent = 'feishu-bot-proxy';
  topbar.appendChild(menuBtn);
  topbar.appendChild(topBrand);
  document.body.insertBefore(topbar, document.body.firstChild);

  // 遮罩（抽屉打开时显示，点击关闭）
  var backdrop = document.createElement('div');
  backdrop.className = 'backdrop';
  backdrop.addEventListener('click', closeDrawer);
  document.body.appendChild(backdrop);

  function openDrawer() { document.body.classList.add('drawer-open'); }
  function closeDrawer() { document.body.classList.remove('drawer-open'); }
  function toggleDrawer() {
    if (document.body.classList.contains('drawer-open')) closeDrawer();
    else openDrawer();
  }

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeDrawer();
  });
})();
