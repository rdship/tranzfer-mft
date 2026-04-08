import { onboardingClient } from './client';

export const login = async (email, password) => {
  const response = await onboardingClient.post('/api/auth/login', { email, password });
  return response.data;
};

export const register = async (email, password) => {
  const response = await onboardingClient.post('/api/auth/register', { email, password });
  return response.data;
};
