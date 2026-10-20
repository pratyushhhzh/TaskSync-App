-- TaskSync Database Schema & Row Level Security (RLS) Policies

-- 1. Profiles Table
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    full_name TEXT,
    timezone TEXT DEFAULT 'UTC',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. Projects Table
CREATE TABLE IF NOT EXISTS public.projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    is_shared BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Project Members Table
CREATE TABLE IF NOT EXISTS public.project_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'member')),
    status TEXT NOT NULL DEFAULT 'accepted' CHECK (status IN ('invited', 'accepted')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(project_id, user_id)
);

-- 4. Sections Table
CREATE TABLE IF NOT EXISTS public.sections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES public.projects(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 5. Tasks Table
CREATE TABLE IF NOT EXISTS public.tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    project_id UUID REFERENCES public.projects(id) ON DELETE SET NULL,
    section_id UUID REFERENCES public.sections(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'inbox' CHECK (status IN ('inbox', 'today', 'upcoming', 'completed')),
    due_date DATE,
    due_time TIME,
    priority INT DEFAULT 4 CHECK (priority BETWEEN 1 AND 4),
    assignee_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    recurrence_rule TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6. Labels Table
CREATE TABLE IF NOT EXISTS public.labels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    color TEXT DEFAULT '#4F46E5',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. Task Labels Table
CREATE TABLE IF NOT EXISTS public.task_labels (
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    label_id UUID NOT NULL REFERENCES public.labels(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, label_id)
);

-- 8. Subtasks Table
CREATE TABLE IF NOT EXISTS public.subtasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    is_completed BOOLEAN DEFAULT FALSE,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 9. Activity Log Table (For Undo and Audit Trail)
CREATE TABLE IF NOT EXISTS public.activity_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    task_id UUID REFERENCES public.tasks(id) ON DELETE SET NULL,
    action_type TEXT NOT NULL,
    old_data JSONB,
    new_data JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ENABLE ROW LEVEL SECURITY (RLS) ON ALL TABLES
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.project_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.subtasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.activity_log ENABLE ROW LEVEL SECURITY;

-- RLS POLICIES FOR PROFILES
CREATE POLICY "Users can view their own profile or profiles of shared project members"
    ON public.profiles FOR SELECT
    USING (
        auth.uid() = id OR
        id IN (
            SELECT pm.user_id FROM public.project_members pm
            WHERE pm.project_id IN (
                SELECT project_id FROM public.project_members WHERE user_id = auth.uid()
            )
        )
    );

CREATE POLICY "Users can update their own profile"
    ON public.profiles FOR UPDATE
    USING (auth.uid() = id);

-- RLS POLICIES FOR PROJECTS
CREATE POLICY "Users can view projects they own or are members of"
    ON public.projects FOR SELECT
    USING (
        owner_id = auth.uid() OR
        id IN (SELECT project_id FROM public.project_members WHERE user_id = auth.uid())
    );

CREATE POLICY "Users can create projects"
    ON public.projects FOR INSERT
    WITH CHECK (owner_id = auth.uid());

CREATE POLICY "Project owners can update their projects"
    ON public.projects FOR UPDATE
    USING (owner_id = auth.uid());

CREATE POLICY "Project owners can delete their projects"
    ON public.projects FOR DELETE
    USING (owner_id = auth.uid());

-- RLS POLICIES FOR TASKS
CREATE POLICY "Users can view personal tasks or shared project tasks"
    ON public.tasks FOR SELECT
    USING (
        user_id = auth.uid() OR
        project_id IN (SELECT project_id FROM public.project_members WHERE user_id = auth.uid())
    );

CREATE POLICY "Users can insert personal tasks or project tasks if member"
    ON public.tasks FOR INSERT
    WITH CHECK (
        user_id = auth.uid() AND (
            project_id IS NULL OR
            project_id IN (SELECT project_id FROM public.project_members WHERE user_id = auth.uid())
        )
    );

CREATE POLICY "Users can update personal tasks or project tasks if member"
    ON public.tasks FOR UPDATE
    USING (
        user_id = auth.uid() OR
        project_id IN (SELECT project_id FROM public.project_members WHERE user_id = auth.uid())
    );

CREATE POLICY "Users can delete personal tasks or project tasks if owner/creator"
    ON public.tasks FOR DELETE
    USING (
        user_id = auth.uid() OR
        project_id IN (SELECT id FROM public.projects WHERE owner_id = auth.uid())
    );
