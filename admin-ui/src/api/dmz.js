import { dmzClient } from './client';

export const listMappings = async () => {
  const response = await dmzClient.get('/api/proxy/mappings');
  return response.data;
};

export const addMapping = async (data) => {
  // data: { name, listenPort, targetHost, targetPort }
  const response = await dmzClient.post('/api/proxy/mappings', data);
  return response.data;
};

export const removeMapping = async (name) => {
  const response = await dmzClient.delete(`/api/proxy/mappings/${encodeURIComponent(name)}`);
  return response.data;
};

export const health = async () => {
  const response = await dmzClient.get('/api/proxy/health');
  return response.data;
};
