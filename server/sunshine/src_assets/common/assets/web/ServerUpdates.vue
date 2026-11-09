<template>
  <section id="server-updates" class="card my-4" v-if="supported">
    <div class="card-body">
      <h2>RHRemote-Sunshine updates</h2>
      <p class="text-body-secondary">Current version: {{ ready.currentVersion || '—' }}<span v-if="status.latestVersion"> · Latest: {{ status.latestVersion }}</span></p>
      <form class="row g-3" @submit.prevent="save">
        <div class="col-12 form-check ms-2">
          <input id="server-auto-update" type="checkbox" class="form-check-input" v-model="settings.enabled" :disabled="busy">
          <label for="server-auto-update" class="form-check-label">Automatically install stable updates when idle</label>
        </div>
        <div class="col-sm-6 col-lg-4">
          <label for="server-update-source" class="form-label">Preferred source</label>
          <select id="server-update-source" class="form-select" v-model="settings.preferredSource" :disabled="busy"><option value="cnb">CNB</option><option value="github">GitHub</option></select>
        </div>
        <div class="col-sm-6 col-lg-4">
          <label for="server-update-interval" class="form-label">Check interval</label>
          <select id="server-update-interval" class="form-select" v-model.number="settings.checkIntervalMinutes" :disabled="busy"><option v-for="minutes in [5,15,30,60]" :key="minutes" :value="minutes">{{ minutes }} minutes</option></select>
        </div>
        <div class="col-12 d-flex flex-wrap gap-3 align-items-center">
          <button type="submit" class="btn btn-outline-primary" :disabled="busy">Save settings</button>
          <button type="button" class="btn btn-primary" :disabled="busy || working" @click="action">{{ working ? 'Updating…' : status.state === 'available' ? 'Download and install' : 'Check for updates' }}</button>
          <span role="status" aria-live="polite">{{ labels[status.state] || status.state }}</span>
        </div>
      </form>
      <p class="text-body-secondary mt-3 mb-0">The other source is tried automatically if the preferred source fails. Active sessions delay installation. Configuration and pairing are preserved.</p>
      <p v-if="status.updatedAt" class="text-body-secondary mt-2 mb-0">Last status: {{ new Date(status.updatedAt * 1000).toLocaleString() }}</p>
      <p v-if="error || status.message" class="mt-3 mb-0" role="alert">{{ error || status.message }}</p>
    </div>
  </section>
</template>
<script>
import { apiFetch } from './fetch_utils'
export default {
  data: () => ({ supported:false, ready:{}, status:{}, settings:{schemaVersion:1,enabled:false,preferredSource:'cnb',checkIntervalMinutes:15}, busy:false, error:'', timer:null,
    labels:{idle:'Ready',checking:'Checking for updates',available:'Update available',waitingForIdle:'Waiting for remote sessions to end',downloading:'Downloading and verifying',installing:'Installing',upToDate:'Up to date',rolledBack:'Update failed; previous version restored',deferred:'Update deferred',error:'Update failed'} }),
  computed:{ working(){ return ['checking','downloading','installing'].includes(this.status.state) } },
  async mounted(){ await this.load(true); this.timer=setInterval(()=>this.load(false),5000) },
  beforeUnmount(){ clearInterval(this.timer) },
  methods:{
    async load(initial){ try { const response=await apiFetch('./api/rhremote/updates'); if(!response.ok) throw Error('Unable to read server update state'); const value=await response.json(); this.supported=value.supported; this.ready=value.ready||{}; this.status=value.status||{}; if(initial) this.settings=value.settings; } catch(e){this.error=e.message} },
    async post(path,value){ const response=await apiFetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)}); const result=await response.json(); if(!response.ok||result.status!==true) throw Error(result.error||'Server update request failed') },
    async save(){ this.busy=true; this.error=''; try{await this.post('./api/rhremote/updates',this.settings); await this.load(false)}catch(e){this.error=e.message}finally{this.busy=false} },
    async action(){ this.busy=true; this.error=''; try{await this.post('./api/rhremote/updates/action',{action:this.status.state==='available'?'install':'check'}); this.status={...this.status,state:'checking'}; }catch(e){this.error=e.message}finally{this.busy=false} }
  }
}
</script>
