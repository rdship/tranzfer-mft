import { onboardingClient } from './client';

export const listMappings = async (accountId) => {
  const response = await onboardingClient.get('/api/folder-mappings', {
    params: { accountId },
  });
  return response.data;
};

export const createMapping = async (data) => {
  // data: { accountId, localPath, remotePath, direction, ... }
  const response = await onboardingClient.post('/api/folder-mappings', data);
  return response.data;
};

export const disableMapping = async (mappingId) => {
  const response = await onboardingClient.patch(`/api/folder-mappings/${mappingId}`, {
    enabled: false,
  });
  return response.data;
};

export const deleteMapping = async (mappingId) => {
  const response = await onboardingClient.delete(`/api/folder-mappings/${mappingId}`);
  return response.data;
};
