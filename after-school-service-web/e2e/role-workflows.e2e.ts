import { expect, type Page, test } from '@playwright/test'

const demoPassword = process.env.E2E_DEMO_PASSWORD ?? '123456'

async function signIn(
  page: Page,
  username: string,
  expectedPath: RegExp,
): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入登录账号').fill(username)
  await page.getByPlaceholder('请输入登录密码').fill(demoPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(expectedPath)
}

async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
}

test('登录页按回车只发送一次认证请求', async ({ page }) => {
  let loginRequests = 0
  page.on('request', (request) => {
    if (request.url().includes('/api/auth/login')) loginRequests += 1
  })

  await page.goto('/login')
  await page.getByPlaceholder('请输入登录账号').fill('admin')
  const password = page.getByPlaceholder('请输入登录密码')
  await password.fill(demoPassword)
  await password.press('Enter')

  await expect(page).toHaveURL(/\/regulator$/)
  expect(loginRequests).toBe(1)
})

test('四角色核心页面与越权路由按服务端角色工作', async ({ page }) => {
  await signIn(page, 'admin', /\/regulator$/)
  await expect(page.getByRole('heading', { name: '课后服务运行总览' })).toBeVisible()
  await page.goto('/regulator/academic')
  await expect(page.getByRole('heading', { name: '学期与服务计划备案' })).toBeVisible()
  await page.goto('/regulator/supervision')
  await expect(page.getByRole('heading', { name: '课后服务预警与复核' })).toBeVisible()
  await page.getByRole('button', { name: '扫描记录' }).click()
  await expect(page.getByText('监管扫描运行记录')).toBeVisible()
  await expect(page.getByText('手工扫描').first()).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByText('监管扫描运行记录')).toBeHidden()
  await signOut(page)

  await signIn(page, 'school_admin', /\/school$/)
  await expect(page.getByRole('heading', { name: '学校课后服务工作台' })).toBeVisible()
  await page.goto('/school/courses')
  await expect(page.getByRole('heading', { name: '课程与开班' })).toBeVisible()
  await page.goto('/school/leave-corrections')
  await expect(page.getByRole('heading', { name: '请假与考勤纠错审批' })).toBeVisible()
  await signOut(page)

  await signIn(page, 'teacher_wang', /\/teacher$/)
  await expect(page.getByRole('heading', { name: '课程与考勤工作台' })).toBeVisible()
  await page.goto('/teacher/sessions')
  await expect(page.getByRole('heading', { name: '课次与学生考勤' })).toBeVisible()
  await page.goto('/teacher/leave-corrections')
  await expect(page.getByRole('heading', { name: '请假审核与考勤纠错' })).toBeVisible()
  await signOut(page)

  await signIn(page, 'parent_chen', /\/parent$/)
  await page.goto('/parent/enrollments')
  await expect(page.getByRole('heading', { name: '为学生选择课后课程' })).toBeVisible()
  await page.goto('/parent/leaves')
  await expect(page.getByRole('heading', { name: '请假从一节课次开始' })).toBeVisible()
  await page.goto('/parent/evaluations')
  await expect(page.getByRole('heading', { name: '课后课程评价' })).toBeVisible()

  await page.goto('/regulator')
  await expect(page).toHaveURL(/\/parent$/)
  await expect(page.getByRole('heading', { name: '学生选课与报名记录' })).toBeVisible()
})

test('长表单取消时保留编辑或显式放弃', async ({ page }) => {
  await signIn(page, 'school_admin', /\/school$/)
  await page.goto('/school/academic')
  await expect(page.getByRole('heading', { name: '学期资源与服务计划' })).toBeVisible()
  await page.getByRole('button', { name: '新增计划' }).click()

  const planDialog = page.getByRole('dialog', { name: '新增服务计划' })
  await planDialog.getByLabel('计划名称').fill('浏览器未保存保护测试')
  await planDialog.getByRole('button', { name: '取消' }).click()
  await expect(page.getByText('当前表单有未保存的修改。放弃修改并继续吗？')).toBeVisible()

  await page.getByRole('button', { name: '继续编辑' }).click()
  await expect(planDialog).toBeVisible()
  await expect(planDialog.getByLabel('计划名称')).toHaveValue('浏览器未保存保护测试')

  await planDialog.getByRole('button', { name: '取消' }).click()
  await page.getByRole('button', { name: '放弃修改' }).click()
  await expect(planDialog).toBeHidden()
})

test('工作台单个接口失败时仍呈现其他模块', async ({ page }) => {
  await signIn(page, 'school_admin', /\/school$/)
  await page.route('**/api/service-plans**', (route) => route.abort('failed'))
  await page.getByRole('button', { name: '刷新数据' }).click()

  await expect(page.getByText('服务计划待办加载失败。')).toBeVisible()
  await expect(page.getByText('课程与开班', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('有效报名', { exact: true }).first()).toBeVisible()
})

test('较慢的旧审批请求不会覆盖最新筛选结果', async ({ page }) => {
  await signIn(page, 'school_admin', /\/school$/)
  await page.goto('/school/leave-corrections')
  await expect(page.getByRole('heading', { name: '请假与考勤纠错审批' })).toBeVisible()

  const leaveRow = (id: number, studentName: string, status: string) => ({
    id,
    schoolId: 1,
    schoolName: '示范实验学校',
    offeringId: 1,
    offeringCode: 'E2E-RACE',
    courseName: '竞态验证课程',
    sessionId: 1,
    sessionDate: '2026-09-08',
    startTime: '16:30:00',
    endTime: '17:30:00',
    studentId: id,
    studentNo: `E2E-${id}`,
    studentName,
    guardianId: id,
    guardianName: '测试家长',
    reason: '验证旧响应不会覆盖最新结果',
    status,
    submittedBy: id,
    submittedByName: '测试家长',
    submittedAt: '2026-08-01T10:00:00',
    reviewedBy: null,
    reviewedByName: null,
    reviewedAt: null,
    reviewRemark: null,
    withdrawnBy: null,
    withdrawnByName: null,
    withdrawnAt: null,
  })

  await page.route('**/api/leave-requests?*', async (route) => {
    const status = new URL(route.request().url()).searchParams.get('status')
    if (status === 'APPROVED') {
      await new Promise((resolve) => setTimeout(resolve, 700))
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([leaveRow(901, '旧响应学生', 'APPROVED')]),
      })
      return
    }
    if (status === 'REJECTED') {
      await new Promise((resolve) => setTimeout(resolve, 40))
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify([leaveRow(902, '最新筛选学生', 'REJECTED')]),
      })
      return
    }
    await route.continue()
  })

  const statusSelect = page.locator('.approval-tabs .status-filter').first()
  const approvedRequest = page.waitForRequest((request) =>
    request.url().includes('/api/leave-requests') &&
    request.url().includes('status=APPROVED'),
  )
  await statusSelect.click()
  await page.getByRole('option', { name: '已批准' }).click()
  await approvedRequest

  const rejectedRequest = page.waitForRequest((request) =>
    request.url().includes('/api/leave-requests') &&
    request.url().includes('status=REJECTED'),
  )
  await statusSelect.click()
  await page.getByRole('option', { name: '已驳回' }).click()
  await rejectedRequest

  await expect(page.getByText('最新筛选学生')).toBeVisible()
  await page.waitForTimeout(800)
  await expect(page.getByText('旧响应学生')).toHaveCount(0)
  await expect(page.getByText('最新筛选学生')).toBeVisible()
})
