<template>
  <nav class="navbar navbar-sunshine">
    <div class="container-fluid">
      <span class="navbar-brand d-flex align-items-center gap-2" title="RHRemote-Sunshine · Based on LizardByte/Sunshine">
        <img src="/images/logo-sunshine-45.png" height="45" alt="Sunshine">
        <span class="fs-6">RHRemote-Sunshine</span>
      </span>
      <ThemeToggle/>
    </div>
  </nav>
</template>

<script>
import ThemeToggle from './ThemeToggle.vue'

export default {
  components: {
    ThemeToggle,
  },
}
</script>
