// 左侧菜单栏：注入到每个页面的 <aside id="sidebar">，并高亮当前页。
(function () {
  var page = document.body.getAttribute('data-page') || '';
  var nav = [
    { key: 'home',    href: '/home.html',    icon: '🏠', label: '首页' },
    { key: 'console', href: '/console.html', icon: '📋', label: '消息查询' },
    { key: 'bots',    href: '/bots.html',    icon: '🤖', label: 'Bot 配置' },
    { key: 'ban-check', href: '/ban-check.html', icon: '🎮', label: '封禁查询' },
    { key: 'accounts', href: '/accounts.html', icon: '👤', label: '账号管理' },
    { key: 'alerts',  href: '/alerts.html',  icon: '🚨', label: '告警配置' },
    { key: 'alert-runs', href: '/alert-runs.html', icon: '🕒', label: '调度日志' }
  ];

  var aside = document.getElementById('sidebar');

  var brand = document.createElement('div');
  brand.className = 'brand';
  brand.textContent = 'feishu-bot-proxy';
  aside.appendChild(brand);

  var navEl = document.createElement('nav');
  nav.forEach(function (item) {
    var a = document.createElement('a');
    a.href = item.href;
    a.innerHTML = '<span class="icon">' + item.icon + '</span>' + item.label;
    if (item.key === page) a.className = 'active';
    navEl.appendChild(a);
  });
  aside.appendChild(navEl);

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
})();
