import { http } from './http'

export type TermStatus = 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'ARCHIVED'
export type PlanStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'FILED'
  | 'RETURNED'
  | 'ACTIVE'
  | 'CLOSED'
  | 'ARCHIVED'
export type RoomStatus = 'ACTIVE' | 'INACTIVE'
export type CalendarDayType =
  | 'TEACHING_DAY'
  | 'MAKEUP_DAY'
  | 'HOLIDAY'
  | 'SUSPENDED'

export interface AcademicTerm {
  id: number
  termCode: string
  termName: string
  startDate: string
  endDate: string
  status: TermStatus
  createdByName?: string
  createdAt?: string
  updatedAt?: string
}

export interface TermInput {
  termCode: string
  termName: string
  startDate: string
  endDate: string
  status: TermStatus
}

export interface ServicePlan {
  id: number
  schoolId: number
  schoolName?: string
  termId: number
  termCode?: string
  termName?: string
  planCode: string
  planName: string
  description: string | null
  status: PlanStatus
  returnReason?: string | null
  submittedAt?: string | null
  filedAt?: string | null
  activatedAt?: string | null
  closedAt?: string | null
  archivedAt?: string | null
  createdByName?: string
  reviewedByName?: string | null
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface ServicePlanInput {
  schoolId?: number
  termId: number
  planCode: string
  planName: string
  description: string | null
}

export interface SchoolRoom {
  id: number
  schoolId: number
  schoolName?: string
  roomCode: string
  roomName: string
  location: string | null
  capacity: number
  status: RoomStatus
  createdByName?: string
  createdAt?: string
  updatedAt?: string
}

export interface RoomInput {
  schoolId?: number
  roomCode: string
  roomName: string
  location: string | null
  capacity: number
  status: RoomStatus
}

export interface CalendarEvent {
  id: number
  schoolId: number
  schoolName?: string
  termId: number
  termCode?: string
  termName?: string
  eventDate: string
  dayType: CalendarDayType
  eventName: string
  description: string | null
  createdByName?: string
  createdAt?: string
  updatedAt?: string
}

export interface CalendarEventInput {
  schoolId?: number
  termId: number
  eventDate: string
  dayType: CalendarDayType
  eventName: string
  description: string | null
}

export interface AcademicOffering {
  id: number
  schoolId: number
  schoolName?: string
  courseId: number
  courseName?: string
  teacherId: number
  teacherName?: string
  offeringCode: string
  term: string
  termId?: number | null
  termCode?: string | null
  termName?: string | null
  planId?: number | null
  planCode?: string | null
  planName?: string | null
  roomId?: number | null
  roomCode?: string | null
  roomName?: string | null
  weekDay: number
  startTime: string
  endTime: string
  startDate: string
  endDate: string
  capacity: number
  classroom: string
  status: 'DRAFT' | 'PUBLISHED' | 'CLOSED' | 'FINISHED' | 'CANCELED'
}

export interface AcademicSession {
  id: number
  schoolId: number
  offeringId: number
  offeringCode?: string
  courseName?: string
  teacherName?: string
  sessionDate: string
  startTime: string
  endTime: string
  classroom: string
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELED'
  notes?: string | null
  recordedCount?: number
  attendedCount?: number
}

export interface ScheduleAdjustment {
  id: number
  schoolId: number
  schoolName?: string
  sessionId: number
  offeringId: number
  offeringCode?: string
  courseName?: string
  teacherId?: number
  teacherName?: string
  originalSessionDate: string
  originalStartTime: string
  originalEndTime: string
  originalRoomId?: number | null
  originalClassroom: string
  adjustedSessionDate: string
  adjustedStartTime: string
  adjustedEndTime: string
  adjustedRoomId: number
  adjustedRoomCode?: string
  adjustedClassroom: string
  reason: string
  status: 'APPLIED' | 'REVERTED'
  requestedByName?: string
  appliedAt: string
  createdAt?: string
}

export interface RescheduleInput {
  sessionDate: string
  startTime: string
  endTime: string
  roomId: number
  reason: string
}

interface ScopeFilters {
  schoolId?: number
  termId?: number
}

export const academicApi = {
  async getTerms(): Promise<AcademicTerm[]> {
    const response = await http.get<AcademicTerm[]>('/terms')
    return response.data
  },
  async createTerm(payload: TermInput): Promise<AcademicTerm> {
    const response = await http.post<AcademicTerm>('/terms', payload)
    return response.data
  },
  async updateTerm(
    id: number,
    payload: TermInput,
  ): Promise<AcademicTerm> {
    const response = await http.put<AcademicTerm>(`/terms/${id}`, payload)
    return response.data
  },

  async getServicePlans(
    filters: ScopeFilters = {},
  ): Promise<ServicePlan[]> {
    const response = await http.get<ServicePlan[]>('/service-plans', {
      params: filters,
    })
    return response.data
  },
  async createServicePlan(
    payload: ServicePlanInput,
  ): Promise<ServicePlan> {
    const response = await http.post<ServicePlan>('/service-plans', payload)
    return response.data
  },
  async updateServicePlan(
    id: number,
    payload: ServicePlanInput,
  ): Promise<ServicePlan> {
    const response = await http.put<ServicePlan>(
      `/service-plans/${id}`,
      payload,
    )
    return response.data
  },
  async transitionServicePlan(
    id: number,
    targetStatus: Exclude<PlanStatus, 'DRAFT'>,
    reason?: string | null,
  ): Promise<ServicePlan> {
    const response = await http.post<ServicePlan>(
      `/service-plans/${id}/transitions`,
      { targetStatus, reason: reason || null },
    )
    return response.data
  },

  async getRooms(schoolId?: number): Promise<SchoolRoom[]> {
    const response = await http.get<SchoolRoom[]>('/rooms', {
      params: { schoolId },
    })
    return response.data
  },
  async createRoom(payload: RoomInput): Promise<SchoolRoom> {
    const response = await http.post<SchoolRoom>('/rooms', payload)
    return response.data
  },
  async updateRoom(id: number, payload: RoomInput): Promise<SchoolRoom> {
    const response = await http.put<SchoolRoom>(`/rooms/${id}`, payload)
    return response.data
  },

  async getCalendarEvents(
    filters: ScopeFilters = {},
  ): Promise<CalendarEvent[]> {
    const response = await http.get<CalendarEvent[]>('/calendar-events', {
      params: filters,
    })
    return response.data
  },
  async createCalendarEvent(
    payload: CalendarEventInput,
  ): Promise<CalendarEvent> {
    const response = await http.post<CalendarEvent>(
      '/calendar-events',
      payload,
    )
    return response.data
  },
  async updateCalendarEvent(
    id: number,
    payload: CalendarEventInput,
  ): Promise<CalendarEvent> {
    const response = await http.put<CalendarEvent>(
      `/calendar-events/${id}`,
      payload,
    )
    return response.data
  },

  async getOfferings(): Promise<AcademicOffering[]> {
    const response = await http.get<AcademicOffering[]>('/offerings')
    return response.data
  },
  async getSessions(offeringId: number): Promise<AcademicSession[]> {
    const response = await http.get<AcademicSession[]>(
      `/offerings/${offeringId}/sessions`,
    )
    return response.data
  },
  async getScheduleAdjustments(
    filters: { schoolId?: number; offeringId?: number } = {},
  ): Promise<ScheduleAdjustment[]> {
    const response = await http.get<ScheduleAdjustment[]>(
      '/schedule-adjustments',
      { params: filters },
    )
    return response.data
  },
  async reschedule(
    sessionId: number,
    payload: RescheduleInput,
  ): Promise<ScheduleAdjustment> {
    const response = await http.post<ScheduleAdjustment>(
      `/sessions/${sessionId}/reschedule`,
      payload,
    )
    return response.data
  },
  async revertScheduleAdjustment(
    adjustmentId: number,
  ): Promise<ScheduleAdjustment> {
    const response = await http.post<ScheduleAdjustment>(
      `/schedule-adjustments/${adjustmentId}/revert`,
    )
    return response.data
  },
}
