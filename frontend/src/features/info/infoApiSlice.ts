import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";

export interface Info {
    name: string,
    primary: Target,
    secondary: Target,
    candidate: Target,
    relativeThreshold: number,
    last_reset: number,
    absoluteThreshold: number,
    listComparisonMode?: 'LEGACY' | 'INDEXED',
    protocol: string,
}
export interface Target {
    target: String
}
export const apiInfoSlice = createApi({
    reducerPath: 'info',
    baseQuery: fetchBaseQuery({
        baseUrl: '/api/1'
    }),
    tagTypes: ['Info'],
    endpoints(builder) {
        return {
            fetchInfo: builder.query<Info, void>({
                query(){
                    return '/info';
                },
                providesTags: ['Info'],
            }),
            setListComparisonMode: builder.mutation<{ listComparisonMode: string }, 'LEGACY' | 'INDEXED'>({
                query(mode) {
                    return {
                        url: '/settings/listComparisonMode',
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: { mode },
                    };
                },
                invalidatesTags: ['Info'],
            }),
            deleteRequests: builder.mutation<string, void>({
                query(){
                    return {
                        url: `/clear`,
                        method: 'GET'
                    }
                },
            })
        }
    },
});

export const {
    useFetchInfoQuery,
    useSetListComparisonModeMutation,
    useDeleteRequestsMutation,
} = apiInfoSlice;
export function fetchinfo(){
    const target = 'Unknown';
    return useFetchInfoQuery().data || {
        name: 'Unknown',
        primary: {target},
        secondary: {target},
        candidate: {target},
        relativeThreshold: 20,
        last_reset: 0,
        absoluteThreshold: 0.03,
        listComparisonMode: 'LEGACY' as const,
        protocol: "http",
    }
}
