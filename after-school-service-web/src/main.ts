import { createApp } from 'vue'

import App from './App.vue'
import { setUnauthorizedHandler } from './api/http'
import router from './router'
import { pinia } from './stores'
import { useSessionStore } from './stores/session'
import './styles/main.css'

const session = useSessionStore(pinia)
setUnauthorizedHandler(() => {
  session.expire()
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({
      name: 'login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  }
})

createApp(App).use(pinia).use(router).mount('#app')
