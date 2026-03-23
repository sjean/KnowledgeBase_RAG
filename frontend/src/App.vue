<template>
  <router-view />
</template>

<style>
:root {
  color-scheme: light;
  --bg: linear-gradient(135deg, #f3efe4 0%, #e0f0ea 55%, #d8e3f8 100%);
  --panel: rgba(255, 255, 255, 0.82);
  --line: rgba(20, 40, 60, 0.12);
  --text: #1f2937;
  --muted: #5b6472;
  --accent: #0f766e;
  --accent-2: #1d4ed8;
  --danger: #c2410c;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  font-family: "Segoe UI", "PingFang SC", sans-serif;
  background: var(--bg);
  color: var(--text);
}

button,
input,
textarea {
  font: inherit;
}

a {
  color: inherit;
}
</style>
