import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('./views/LayoutView.vue'),
    children: [
      { path: '', redirect: '/scenarios' },
      { path: 'scenarios', component: () => import('./views/ScenariosView.vue') },
      { path: 'approvals', component: () => import('./views/ApprovalsView.vue') },
      { path: 'cohorts', component: () => import('./views/CohortsView.vue') },
      { path: 'query', component: () => import('./views/QueryView.vue') },
      { path: 'table-sets', component: () => import('./views/TableSetView.vue') },
      { path: 'deliverables', component: () => import('./views/DeliverablesView.vue') },
      { path: 'link-versions', component: () => import('./views/LinkVersionsView.vue') },
      { path: 'budget', component: () => import('./views/BudgetView.vue') },
      { path: 'audit', component: () => import('./views/AuditView.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) return '/login'
})
export default router
