import { configClient, onboardingClient } from './client';

export const listServers = async () => {
  const response = await configClient.get('/api/servers');
  return response.data;
};

export const addServer = async (data) => {
  const response = await configClient.post('/api/servers', data);
  return response.data;
};

export const toggleServer = async (serverId, enabled) => {
  const response = await configClient.patch(`/api/servers/${serverId}`, { enabled });
  return response.data;
};

export const listLegacyServers = async () => {
  const response = await configClient.get('/api/legacy-servers');
  return response.data;
};

export const addLegacyServer = async (data) => {
  const response = await configClient.post('/api/legacy-servers', data);
  return response.data;
};

export const addDestination = async (data) => {
  const response = await configClient.post('/api/external-destinations', data);
  return response.data;
};

export const listDestinations = async () => {
  const response = await configClient.get('/api/external-destinations');
  return response.data;
};

export const serviceRegistry = async () => {
  const response = await onboardingClient.get('/api/service-registry');
  return response.data;
};
